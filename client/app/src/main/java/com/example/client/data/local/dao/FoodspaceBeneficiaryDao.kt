package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FoodspaceBeneficiaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(record: FoodspaceBeneficiaryRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(records: List<FoodspaceBeneficiaryRecord>)

    @Update
    abstract suspend fun update(record: FoodspaceBeneficiaryRecord)

    @Query("SELECT * FROM foodspace_beneficiary_records")
    abstract fun getAll(): Flow<List<FoodspaceBeneficiaryRecord>>

    /** The cached records for the Vetting list, A to Z. Updates live when a fetch replaces the cache. */
    @Query("SELECT * FROM foodspace_beneficiary_records ORDER BY legalName COLLATE NOCASE ASC, id ASC")
    abstract fun observeAllByName(): Flow<List<FoodspaceBeneficiaryRecord>>

    @Query("SELECT * FROM foodspace_beneficiary_records WHERE id = :id LIMIT 1")
    abstract fun observeById(id: String): Flow<FoodspaceBeneficiaryRecord?>

    @Query("SELECT * FROM foodspace_beneficiary_records WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): FoodspaceBeneficiaryRecord?

    @Query("DELETE FROM foodspace_beneficiary_records")
    abstract suspend fun deleteAll()

    /**
     * Fetch-and-replace (this table is read-only for the rest of the app): swaps the whole cache in one transaction,
     * so a reader sees the old list or the new one, never half of each, and a failed write keeps the old list.
     */
    @Transaction
    open suspend fun replaceAll(records: List<FoodspaceBeneficiaryRecord>) {
        deleteAll()
        insertAll(records)
    }
}
