package com.worldexplorer.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MigrationsTest {
    @Test
    fun `every schema version has an exported schema and a migration from the previous one`() {
        val dir = File("schemas/${WorldExplorerDatabase::class.java.name}")
        for (v in 1..WorldExplorerDatabase.VERSION) assertTrue("schema $v.json exported", File(dir, "$v.json").exists())
        val covered = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        for (v in 2..WorldExplorerDatabase.VERSION) assertTrue("migration ${v - 1}->$v", (v - 1 to v) in covered)
        assertEquals(WorldExplorerDatabase.VERSION - 1, DatabaseMigrations.ALL.size)
    }
}
