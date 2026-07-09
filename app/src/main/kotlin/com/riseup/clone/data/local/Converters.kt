package com.riseup.clone.data.local

import androidx.room.TypeConverter
import com.riseup.clone.domain.model.AccountType
import com.riseup.clone.domain.model.Category
import java.time.LocalDate

/**
 * Bridges domain types that SQLite has no native column for.
 *
 * Dates are stored as epoch-day [Long] so ordering by date is a plain numeric
 * sort; enums are stored by [Enum.name] (stable, human-readable) rather than
 * ordinal, so reordering an enum can never silently reinterpret old rows.
 */
class Converters {

    @TypeConverter
    fun localDateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(epochDay: Long?): LocalDate? =
        epochDay?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun categoryToName(category: Category?): String? = category?.name

    @TypeConverter
    fun nameToCategory(name: String?): Category? = name?.let(Category::valueOf)

    @TypeConverter
    fun accountTypeToName(type: AccountType?): String? = type?.name

    @TypeConverter
    fun nameToAccountType(name: String?): AccountType? = name?.let(AccountType::valueOf)
}
