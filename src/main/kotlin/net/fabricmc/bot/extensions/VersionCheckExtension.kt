package net.fabricmc.bot.extensions

import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.checks.topRoleHigherOrEqual
import com.kotlindiscord.kord.extensions.commands.converters.string
import com.kotlindiscord.kord.extensions.commands.parser.Arguments
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.sentry.tag
import com.kotlindiscord.kord.extensions.utils.respond
import dev.kord.common.entity.ChannelType
import dev.kord.core.event.gateway.ReadyEvent
import io.ktor.client.HttpClient
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.request.get
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import net.fabricmc.bot.conf.config
import net.fabricmc.bot.constants.Colors
import net.fabricmc.bot.defaultCheck
import net.fabricmc.bot.enums.Roles
import net.fabricmc.bot.events.LatestMinecraftVersionsRetrieved
import net.fabricmc.bot.utils.requireMainGuild

private const val UPDATE_CHECK_DELAY = 1000L * 30L  // 30 seconds, consider kotlin.time when it's not experimental
private const val SETUP_DELAY = 1000L * 10L  // 10 seconds

private var JIRA_URL = "https://bugs.mojang.com/rest/api/latest/project/MC/versions"
private var MINECRAFT_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

private val logger = KotlinLogging.logger {}

/**
 * Automatic updates on new Minecraft versions, in Jira and launchermeta.
 */
class VersionCheckExtension(bot: ExtensibleBot) : Extension(bot) {
    override val name = "version check"

    private val client = HttpClient {
        install(JsonFeature) {
            serializer = KotlinxSerializer(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    private var minecraftVersions = listOf<MinecraftVersion>()
    private var jiraVersions = listOf<JiraVersion>()
    private var checkJob: Job? = null
    private var currentlyChecking = false

    private var latestVersion: MinecraftLatest? = null

    /** The latest Minecraft release, if we've had a chance to get it. **/
    val latestRelease get() = latestVersion?.release

    /** The latest Minecraft snapshot, if we've had a chance to get it. **/
    val latestSnapshot get() = latestVersion?.snapshot

    override suspend fun setup() {
        val environment = System.getenv().getOrDefault("ENVIRONMENT", "production")

        event<ReadyEvent> {
            action {
                currentlyChecking = true

                logger.info { "Delaying setup to ensure everything is cached." }
                delay(SETUP_DELAY)

                if (config.getMinecraftUpdateChannels().isEmpty() && config.getJiraUpdateChannels().isEmpty()) {
                    logger.warn { "No channels are configured, not enabling version checks." }

                    return@action // No point if we don't have anywhere to post.
                }

                logger.info { "Fetching initial data." }

                breadcrumb(
                    category = "event.ready",
                    type = "debug",

                    message = "Fetching initial data"
                )

                minecraftVersions = getMinecraftVersions()
                jiraVersions = getJiraVersions()

                breadcrumb(
                    category = "event.ready",
                    type = "debug",

                    message = "Initial versions fetched, scheduling check job",

                    data = mapOf(
                        "minecraft.release" to (latestVersion?.release ?: "N/A"),
                        "minecraft.snapshot" to (latestVersion?.snapshot ?: "N/A")
                    )
                )

                currentlyChecking = false

                logger.debug { "Scheduling check job." }

                checkJob = bot.kord.launch {
                    while (true) {
                        delay(UPDATE_CHECK_DELAY)

                        logger.debug { "Running scheduled check." }

                        val breadcrumbs = mutableListOf<Breadcrumb>()

                        @Suppress("TooGenericExceptionCaught")
                        try {
                            updateCheck(breadcrumbs)
                        } catch (t: Throwable) {
                            val sentry = extension.bot.sentry

                            if (sentry.enabled) {
                                Sentry.withScope {
                                    it.tag("extension", name)
                                    it.tag("event", event::class.simpleName ?: "Unknown")

                                    breadcrumbs.forEach { breadcrumb -> it.addBreadcrumb(breadcrumb) }

                                    Sentry.captureException(t, "Failed to check for Minecraft version updates.")
                                }
                            }

                            logger.catching(t)
                        }
                    }
                }

                logger.info { "Ready to go!" }
            }
        }

        command {
            name = "versioncheck"
            description = "Force running a version check for Jira and Minecraft, for when you can't wait 30 seconds."

            check(
                ::defaultCheck,
                topRoleHigherOrEqual(config.getRole(Roles.MODERATOR))
            )

            action {
                if (!message.requireMainGuild(Roles.ADMIN)) {
                    return@action
                }

                if (currentlyChecking) {
                    message.respond("A version check is already running - try again later!")

                    return@action
                }

                message.respond(
                    "Manually executing a version check."
                )

                logger.debug { "Version check requested by command." }

                currentlyChecking = true

                breadcrumb(
                    category = "command.versioncheck",
                    type = "debug",

                    message = "Manually executing a version check"
                )

                @Suppress("TooGenericExceptionCaught")
                try {
                    updateCheck(breadcrumbs)

                    message.respond {
                        embed {
                            title = "Version check success"
                            color = Colors.POSITIVE

                            description = "Successfully checked for new Minecraft versions and JIRA releases."

                            field {
                                name = "Latest (JIRA)"
                                value = jiraVersions.last().name

                                inline = true
                            }

                            field {
                                name = "Latest (Minecraft)"
                                value = minecraftVersions.first().id

                                inline = true
                            }
                        }
                    }
                } finally {
                    currentlyChecking = false
                }
            }
        }

        if (environment != "production") {
            logger.debug { "Registering debugging commands for admins: jira-url and mc-url" }

            command {
                name = "jira-url"
                description = "Change the JIRA update URL, for debugging."

                aliases = arrayOf("jiraurl")

                signature(::UrlCommandArguments)

                check(
                    ::defaultCheck,
                    topRoleHigherOrEqual(config.getRole(Roles.ADMIN))
                )

                action {
                    with(parse(::UrlCommandArguments)) {
                        JIRA_URL = url

                        message.respond(
                            "JIRA URL updated to `$url`."
                        )
                    }
                }
            }

            command {
                name = "mc-url"
                description = "Change the MC update URL, for debugging."

                aliases = arrayOf("mcurl")

                signature(::UrlCommandArguments)

                check(
                    ::defaultCheck,
                    topRoleHigherOrEqual(config.getRole(Roles.ADMIN))
                )

                action {
                    with(parse(::UrlCommandArguments)) {
                        MINECRAFT_URL = url

                        message.respond(
                            "MC URL updated to `$url`."
                        )
                    }
                }
            }
        }
    }

    override suspend fun unload() {
        logger.debug { "Extension unloaded, cancelling job." }

        checkJob?.cancel()
    }

    private suspend fun updateCheck(breadcrumbs: MutableList<Breadcrumb>? = null) {
        if (currentlyChecking) {
            logger.warn { "Looks like multiple checks are running concurrently - skipping check." }
            return
        }

        currentlyChecking = true

        @Suppress("TooGenericExceptionCaught")
        try {
            breadcrumbs?.add(
                bot.sentry.createBreadcrumb(
                    category = "minecraft",
                    type = "debug",

                    message = "Checking for Minecraft version updates"
                )
            )

            val mc = checkForMinecraftUpdates(breadcrumbs)

            if (mc != null) {
                breadcrumbs?.add(
                    bot.sentry.createBreadcrumb(
                        category = "minecraft",
                        type = "debug",

                        message = "Relaying new Minecraft version",
                        data = mapOf(
                            "version.type" to mc.type,
                            "version.id" to mc.id
                        )
                    )
                )

                config.getMinecraftUpdateChannels().forEach {
                    val message = it.createMessage(
                        "A new Minecraft ${mc.type} is out: ${mc.id}"
                    )

                    if (it.type == ChannelType.GuildNews) {
                        message.publish()
                    }
                }
            }

            breadcrumbs?.add(
                bot.sentry.createBreadcrumb(
                    category = "minecraft",
                    type = "debug",

                    message = "Checking for JIRA updates"
                )
            )

            val jira = checkForJiraUpdates(breadcrumbs)

            if (jira != null) {
                breadcrumbs?.add(
                    bot.sentry.createBreadcrumb(
                        category = "minecraft",
                        type = "debug",

                        message = "Relaying JIRA update",
                        data = mapOf(
                            "version.name" to jira.name,
                            "version.id" to jira.id
                        )
                    )
                )

                config.getJiraUpdateChannels().forEach {
                    val message = it.createMessage(
                        "A new version (${jira.name}) has been added to the Minecraft issue tracker!"
                    )

                    if (it.type == ChannelType.GuildNews) {
                        message.publish()
                    }
                }
            }
        } finally {
            currentlyChecking = false
        }
    }

    private suspend fun checkForMinecraftUpdates(breadcrumbs: MutableList<Breadcrumb>? = null): MinecraftVersion? {
        logger.debug { "Checking for Minecraft updates." }

        val versions = getMinecraftVersions(breadcrumbs)
        val new = versions.find { it !in minecraftVersions }

        logger.debug { "Minecraft | New version: ${new ?: "N/A"}" }
        logger.debug { "Minecraft | Total versions: " + versions.size }

        minecraftVersions = versions

        return new
    }

    private suspend fun checkForJiraUpdates(breadcrumbs: MutableList<Breadcrumb>? = null): JiraVersion? {
        logger.debug { "Checking for JIRA updates." }

        val versions = getJiraVersions(breadcrumbs)
        val new = versions.find { it !in jiraVersions && "future version" !in it.name.toLowerCase() }

        logger.debug { "     JIRA | New release: ${new ?: "N/A"}" }

        jiraVersions = versions

        return new
    }

    private suspend fun getJiraVersions(breadcrumbs: MutableList<Breadcrumb>? = null): List<JiraVersion> {
        val response = client.get<List<JiraVersion>>(JIRA_URL)

        breadcrumbs?.add(
            bot.sentry.createBreadcrumb(
                category = "http",
                type = "http",

                message = "Retrieved ${response.size} JIRA versions",

                data = mapOf(
                    "url" to JIRA_URL,
                    "method" to "GET"
                )
            )
        )

        logger.debug { "     JIRA | Latest release: " + response.last().name }
        logger.debug { "     JIRA | Total releases: " + response.size }

        return response
    }

    private suspend fun getMinecraftVersions(breadcrumbs: MutableList<Breadcrumb>? = null): List<MinecraftVersion> {
        val response = client.get<LauncherMetaResponse>(MINECRAFT_URL)

        breadcrumbs?.add(
            bot.sentry.createBreadcrumb(
                category = "http",
                type = "http",

                message = "Retrieved ${response.versions.size} Minecraft versions",

                data = mapOf(
                    "url" to MINECRAFT_URL,
                    "method" to "GET"
                )
            )
        )

        latestVersion = response.latest

        bot.send(LatestMinecraftVersionsRetrieved(bot, response.latest))

        logger.debug { "Minecraft | Latest release: " + response.latest.release }
        logger.debug { "Minecraft | Latest snapshot: " + response.latest.snapshot }

        return response.versions
    }


    /** @suppress **/
    @Suppress("UndocumentedPublicProperty")
    class UrlCommandArguments : Arguments() {
        val url by string("url")
    }
}

@Serializable
private data class MinecraftVersion(
    val id: String,
    val type: String,
)

/**
 * Data class representing the latest versions for Minecraft.
 *
 * @param release Latest release version
 * @param snapshot Latest snapshot version
 */
@Serializable
data class MinecraftLatest(
    val release: String,
    val snapshot: String,
)

@Serializable
private data class LauncherMetaResponse(
    val versions: List<MinecraftVersion>,
    val latest: MinecraftLatest
)

@Serializable
private data class JiraVersion(
    val id: String,
    val name: String,
)
