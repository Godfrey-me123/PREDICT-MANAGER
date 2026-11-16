package com.example.data.database

import androidx.room.TypeConverter
import com.example.data.model.CollectionSource
import com.example.data.model.ColorMatchDirection
import com.example.data.model.CrashColorCategory
import com.example.data.model.HistoryCollectionStatus
import com.example.data.model.MultiplierRuleSubtype
import com.example.data.model.NotificationDeliveryStatus
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.PatternLogicOperator
import com.example.data.model.RepeatNotificationPolicy
import com.example.data.model.StrategyGroup
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyNotificationType
import com.example.data.model.TimestampSource

class ScreenEarnerTypeConverters {

    @TypeConverter
    fun fromHistoryCollectionStatus(value: HistoryCollectionStatus): String = value.name

    @TypeConverter
    fun toHistoryCollectionStatus(value: String): HistoryCollectionStatus =
        runCatching { HistoryCollectionStatus.valueOf(value) }
            .getOrDefault(HistoryCollectionStatus.HISTORY_PARTIAL)

    @TypeConverter
    fun fromTimestampSource(value: TimestampSource): String = value.name

    @TypeConverter
    fun toTimestampSource(value: String): TimestampSource =
        runCatching { TimestampSource.valueOf(value) }
            .getOrDefault(TimestampSource.LOCAL_OBSERVATION)

    @TypeConverter
    fun fromCollectionSource(value: CollectionSource): String = value.name

    @TypeConverter
    fun toCollectionSource(value: String): CollectionSource =
        runCatching { CollectionSource.valueOf(value) }
            .getOrDefault(CollectionSource.LIVE_CRASH)

    @TypeConverter
    fun fromCrashColorCategory(value: CrashColorCategory): String = value.name

    @TypeConverter
    fun toCrashColorCategory(value: String): CrashColorCategory =
        runCatching { CrashColorCategory.valueOf(value) }
            .getOrDefault(CrashColorCategory.Other)

    @TypeConverter
    fun fromStrategyMode(value: StrategyMode): String = value.name

    @TypeConverter
    fun toStrategyMode(value: String): StrategyMode =
        runCatching { StrategyMode.valueOf(value) }
            .getOrDefault(StrategyMode.CUSTOM_CONDITION)

    @TypeConverter
    fun fromStrategyGroup(value: StrategyGroup): String = value.name

    @TypeConverter
    fun toStrategyGroup(value: String): StrategyGroup =
        runCatching { StrategyGroup.valueOf(value) }
            .getOrDefault(StrategyGroup.MULTIPLIER_INTERVAL)

    @TypeConverter
    fun fromMultiplierRuleSubtype(value: MultiplierRuleSubtype): String = value.name

    @TypeConverter
    fun toMultiplierRuleSubtype(value: String): MultiplierRuleSubtype =
        runCatching { MultiplierRuleSubtype.valueOf(value) }
            .getOrDefault(MultiplierRuleSubtype.STANDARD_THRESHOLD)

    @TypeConverter
    fun fromColorMatchDirection(value: ColorMatchDirection): String = value.name

    @TypeConverter
    fun toColorMatchDirection(value: String): ColorMatchDirection =
        runCatching { ColorMatchDirection.valueOf(value) }
            .getOrDefault(ColorMatchDirection.HORIZONTAL)

    @TypeConverter
    fun fromPatternLogicOperator(value: PatternLogicOperator): String = value.name

    @TypeConverter
    fun toPatternLogicOperator(value: String): PatternLogicOperator =
        runCatching { PatternLogicOperator.valueOf(value) }
            .getOrDefault(PatternLogicOperator.AND)

    @TypeConverter
    fun fromNotificationPriorityLevel(value: NotificationPriorityLevel): String = value.name

    @TypeConverter
    fun toNotificationPriorityLevel(value: String): NotificationPriorityLevel =
        runCatching { NotificationPriorityLevel.valueOf(value) }
            .getOrDefault(NotificationPriorityLevel.HIGH)

    @TypeConverter
    fun fromRepeatNotificationPolicy(value: RepeatNotificationPolicy): String = value.name

    @TypeConverter
    fun toRepeatNotificationPolicy(value: String): RepeatNotificationPolicy =
        runCatching { RepeatNotificationPolicy.valueOf(value) }
            .getOrDefault(RepeatNotificationPolicy.ONCE_PER_OCCURRENCE)

    @TypeConverter
    fun fromStrategyNotificationType(value: StrategyNotificationType): String = value.name

    @TypeConverter
    fun toStrategyNotificationType(value: String): StrategyNotificationType =
        runCatching { StrategyNotificationType.valueOf(value) }
            .getOrDefault(StrategyNotificationType.STRATEGY_REACHED)

    @TypeConverter
    fun fromNotificationDeliveryStatus(value: NotificationDeliveryStatus): String = value.name

    @TypeConverter
    fun toNotificationDeliveryStatus(value: String): NotificationDeliveryStatus =
        runCatching { NotificationDeliveryStatus.valueOf(value) }
            .getOrDefault(NotificationDeliveryStatus.FAILED)

    @TypeConverter
    fun fromNotificationInteractionStatus(value: NotificationInteractionStatus): String = value.name

    @TypeConverter
    fun toNotificationInteractionStatus(value: String): NotificationInteractionStatus =
        runCatching { NotificationInteractionStatus.valueOf(value) }
            .getOrDefault(NotificationInteractionStatus.UNREAD)
}
