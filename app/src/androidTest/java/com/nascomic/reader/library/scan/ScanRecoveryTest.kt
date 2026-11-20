package com.nascomic.reader.library.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 上一次扫描没走完时留下的「扫描中」要能收尾。
 *
 * 扫描开始时先单独提交「扫描中」，进程中途被杀时入库事务回滚了、状态却留着——
 * 界面会一直转，用户也重试不了。
 */
@RunWith(AndroidJUnit4::class)
class ScanRecoveryTest {

    private lateinit var db: ReaderDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `停在扫描中的来源改成中断_其他状态不动`() = runBlocking {
        val stuck = source("卡住的", SourceEntity.STATE_SCANNING)
        val done = source("扫完的", SourceEntity.STATE_DONE)
        val failed = source("失败的", SourceEntity.STATE_FAILED, SourceEntity.ERROR_AUTH)

        ScanRecovery.recover(db)

        val stuckAfter = db.sourceDao().get(stuck)
        assertEquals(SourceEntity.STATE_FAILED, stuckAfter?.scanState)
        assertEquals(SourceEntity.ERROR_INTERRUPTED, stuckAfter?.errorCode)

        assertEquals(SourceEntity.STATE_DONE, db.sourceDao().get(done)?.scanState)
        assertEquals(SourceEntity.ERROR_AUTH, db.sourceDao().get(failed)?.errorCode)
    }

    private suspend fun source(label: String, state: Int, error: Int? = null): Long =
        db.sourceDao().insert(
            SourceEntity(
                kind = SourceEntity.KIND_WEBDAV,
                label = label,
                endpoint = "http://127.0.0.1/dav/",
                addedAt = 1000,
                scanState = state,
                errorCode = error,
            )
        )
}
