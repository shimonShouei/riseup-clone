package com.riseup.clone.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.riseup.clone.domain.model.Category
import java.time.LocalDate

/**
 * Room row for a [com.riseup.clone.domain.model.Transaction].
 *
 * The [accountId] is a foreign key onto [AccountEntity]; deleting an account
 * cascades to its transactions so the store can never hold orphaned entries.
 * The index keeps per-account queries cheap once real sync writes many rows.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val date: LocalDate,
    val amount: Double,
    val merchant: String,
    val category: Category,
)
