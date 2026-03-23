package com.example.keyring.data

/** 密码列表排序方式（名称或更新时间，升序或降序）。 */
enum class PasswordListSortOrder {
    /** 名称 A→Z（按当前语言区域规则） */
    NAME_ASC,

    /** 名称 Z→A */
    NAME_DESC,

    /** 更新时间：旧→新 */
    TIME_ASC,

    /** 更新时间：新→旧 */
    TIME_DESC
}
