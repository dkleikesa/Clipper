package com.qcmian.clipper.core.data.local

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 用户偏好设置，存为单行序列化结果。
 *
 * 偏好设置是一个整体读写的值对象，因此用一行 JSON 让表结构保持稳定，同时各项偏好可以继续变化。
 * 若把它们拆成带类型的列，每新增一个选项都要改表结构。
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int,
    val payload: String,
) {
    companion object {
        /** 偏好设置固定存在单行中。 */
        const val SINGLE_ROW_ID = 0
    }
}
