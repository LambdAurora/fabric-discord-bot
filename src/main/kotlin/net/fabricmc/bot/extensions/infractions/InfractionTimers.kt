package net.fabricmc.bot.extensions.infractions

import com.gitlab.kordlib.common.entity.Snowflake
import com.gitlab.kordlib.core.behavior.channel.createEmbed
import com.gitlab.kordlib.core.entity.channel.TextChannel
import com.kotlindiscord.kord.extensions.utils.Scheduler
import net.fabricmc.bot.conf.config
import net.fabricmc.bot.constants.Colours
import net.fabricmc.bot.database.Infraction
import net.fabricmc.bot.enums.Channels
import net.fabricmc.bot.enums.InfractionTypes
import net.fabricmc.bot.enums.Roles
import net.fabricmc.bot.runSuspended
import java.time.Duration
import java.time.Instant
import java.util.*

private val jobs: MutableMap<UUID, UUID> = mutableMapOf()
private var scheduler = Scheduler()

private val queries = config.db.infractionQueries

/**
 * Schedule the end of an infraction.
 *
 * @param id The ID of the user that was infracted.
 * @param infraction The infraction object from the database.
 * @param time An [Instant] representing the expiry time of the infraction.
 */
suspend fun scheduleUndoInfraction(id: Long, infraction: Infraction, time: Instant) {
    when (infraction.infraction_type) {
        InfractionTypes.BAN -> unbanAt(id, infraction, time)
        InfractionTypes.META_MUTE -> unMetaMuteAt(id, infraction, time)
        InfractionTypes.MUTE -> unMuteAt(id, infraction, time)
        InfractionTypes.REACTION_MUTE -> unReactionMuteAt(id, infraction, time)
        InfractionTypes.REQUESTS_MUTE -> unRequestsMuteAt(id, infraction, time)
        InfractionTypes.SUPPORT_MUTE -> unSupportMuteAtAt(id, infraction, time)

        else -> null  // Do nothing
    }
}

/**
 * Automatically unban a user (by ID) at a given [Instant].
 *
 * @param id The ID of the user to unban.
 * @param time The [Instant] representing the time to remove the ban.
 */
suspend fun unbanAt(id: Long, infraction: Infraction, time: Instant) {
    schedule(getDelayFromNow(time), infraction) {
        config.getGuild().unban(Snowflake(id))
    }
}

/**
 * Automatically unmute a user (by ID) at a given [Instant].
 *
 * @param id The ID of the user to unban.
 * @param time The [Instant] representing the time to remove the mute.
 */
suspend fun unMuteAt(id: Long, infraction: Infraction, time: Instant) {
    schedule(getDelayFromNow(time), infraction) {
        val member = config.getGuild().getMemberOrNull(Snowflake(id)) ?: return@schedule

        member.removeRole(config.getRoleSnowflake(Roles.MUTED), "Expiring temporary mute")
    }
}

/**
 * Automatically un-meta-mute a user (by ID) at a given [Instant].
 *
 * @param id The ID of the user to unban.
 * @param time The [Instant] representing the time to remove the mute.
 */
suspend fun unMetaMuteAt(id: Long, infraction: Infraction, time: Instant) {
    schedule(getDelayFromNow(time), infraction) {
        val member = config.getGuild().getMemberOrNull(Snowflake(id)) ?: return@schedule

        member.removeRole(config.getRoleSnowflake(Roles.NO_META), "Expiring temporary meta-mute")
    }
}

/**
 * Automatically un-reaction-mute a user (by ID) at a given [Instant].
 *
 * @param id The ID of the user to unban.
 * @param time The [Instant] representing the time to remove the mute.
 */
suspend fun unReactionMuteAt(id: Long, infraction: Infraction, time: Instant) {
    schedule(getDelayFromNow(time), infraction) {
        val member = config.getGuild().getMemberOrNull(Snowflake(id)) ?: return@schedule

        member.removeRole(config.getRoleSnowflake(Roles.NO_REACTIONS), "Expiring temporary reaction-mute")
    }
}

/**
 * Automatically un-requests-mute a user (by ID) at a given [Instant].
 *
 * @param id The ID of the user to unban.
 * @param time The [Instant] representing the time to remove the mute.
 */
suspend fun unRequestsMuteAt(id: Long, infraction: Infraction, time: Instant) {
    schedule(getDelayFromNow(time), infraction) {
        val member = config.getGuild().getMemberOrNull(Snowflake(id)) ?: return@schedule

        member.removeRole(config.getRoleSnowflake(Roles.NO_REQUESTS), "Expiring temporary requests mute")
    }
}

/**
 * Automatically un-support-mute a user (by ID) at a given [Instant].
 *
 * @param id The ID of the user to unban.
 * @param time The [Instant] representing the time to remove the mute.
 */
suspend fun unSupportMuteAtAt(id: Long, infraction: Infraction, time: Instant) {
    schedule(getDelayFromNow(time), infraction) {
        val member = config.getGuild().getMemberOrNull(Snowflake(id)) ?: return@schedule

        member.removeRole(config.getRoleSnowflake(Roles.NO_SUPPORT), "Expiring temporary support mute")
    }
}

/**
 * Cancel all pending infraction reversal jobs.
 *
 * This is useful if we need to reset all the jobs - for example, if the bot reconnects.
 */
fun clearJobs() {
    scheduler.cancelAll()
    jobs.clear()

    scheduler = Scheduler()
}

/**
 * Cancels the pending infraction reversal job for a specific infraction.
 *
 * This is useful if an infraction is pardoned early.
 */
fun cancelJobForInfraction(infraction: UUID) {
    scheduler.cancelJob(jobs[infraction] ?: return)
    jobs.remove(infraction)
}

private fun schedule(delay: Long, infraction: Infraction, callback: suspend (Nothing?) -> Unit) {
    jobs[UUID.fromString(infraction.id)] = scheduler.schedule(delay, null) {
        callback.invoke(it)

        runSuspended {
            val inf = queries.getInfraction(infraction.id).executeAsOne()

            queries.setInfractionActive(
                    false,
                    inf.id
            )
        }

        val modLog = config.getChannel(Channels.MODERATOR_LOG) as TextChannel

        modLog.createEmbed {
            title = "Infraction Expired"
            color = Colours.BLURPLE

            description = "<@${infraction.target_id}> (`${infraction.target_id}`) is no longer " +
                    "${infraction.infraction_type.actionText}."

            footer {
                text = "ID: ${infraction.id}"
            }
        }
    }
}

/**
 * Returns the number of milliseconds until the given time, from the current time.
 *
 * Returns 0 if the time given is in the past.
 *
 * @param time Time to compare to now.
 */
fun getDelayFromNow(time: Instant): Long {
    if (time <= Instant.now()) {
        return 0L
    }

    val duration = Duration.between(Instant.now(), time)

    return duration.toMillis()
}