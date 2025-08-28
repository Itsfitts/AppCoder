/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.testing.android.rules

import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.log.LogLevel
import io.realm.log.RealmLog
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Rule for Realm DB tests.
 *
 * Uses a dedicated Realm under the app's cache directory for each test name.
 */
class RealmDBTestRule(
  val baseModule: Any? = null,
  vararg val additionalModules: Any,
) : AbstractAndroidTestRule() {

  fun withDb(
    dbName: String,
    deleteDbAfterTest: Boolean = true,
    action: Realm.() -> Unit
  ) {
    // Make the name filesystem-safe
    val safeName = dbName.replace('/', '-').replace('\\', '-')

    // Create a per-test directory under cache
    val dir = File(context.applicationContext.cacheDir, "realm-tests/$safeName").apply { mkdirs() }

    val config = RealmConfiguration.Builder()
      .directory(dir)
      .name("$safeName.realm")
      // If your Realm version supports modules and you need them, add here.
      // The generic builder.modules(...) call is omitted for broad compatibility.
      .build()

    val realm = Realm.getInstance(config)
    try {
      realm.action()
    } finally {
      realm.close()
      if (deleteDbAfterTest) {
        dir.deleteRecursively()
      }
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        Realm.init(context.applicationContext)
        RealmLog.setLevel(LogLevel.ALL)
        base.evaluate()
      }
    }
  }
}