package com.sleepissue.awakedrive.demo

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Room와 작은 상태 저장소의 이름을 한 번 더 격리한다. REAL 파일은 열지 않는다. */
class DemoStorageContext(context: Context, private val namespace: String) : ContextWrapper(context.applicationContext) {
    init { require(namespace.matches(Regex("[A-Za-z0-9_-]+"))) }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File = baseContext.getDatabasePath("demo_${namespace}_$name")
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("demo_${namespace}_$name", mode)
}
