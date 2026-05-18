package com.lambliver.appforsale.ui

/** 自 Context 往上 unwrap ContextWrapper，尋找外層的 `ComponentActivity`。全程使用全限定名稱以降低 IDE 對 `import android.content` 的假性 Unresolved。 */
fun android.content.Context.findActivity(): androidx.activity.ComponentActivity? {
    var cursor: android.content.Context = this
    while (cursor is android.content.ContextWrapper) {
        if (cursor is androidx.activity.ComponentActivity) return cursor
        cursor = cursor.baseContext
    }
    return null
}
