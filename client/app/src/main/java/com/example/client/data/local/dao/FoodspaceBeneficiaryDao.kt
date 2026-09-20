package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodspaceBeneficiaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: FoodspaceBeneficiaryRecord)

    @Update
    suspend fun update(record: FoodspaceBeneficiaryRecord)

    @Query("SELECT * FROM foodspace_beneficiary_records")
    fun getAll(): Flow<List<FoodspaceBeneficiaryRecord>>
    
    @Query("SELECT * FROM foodspace_beneficiary_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FoodspaceBeneficiaryRecord?
}
