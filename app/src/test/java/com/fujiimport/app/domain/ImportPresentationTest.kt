package com.fujiimport.app.domain

import com.fujiimport.app.ui.SelectionState
import org.junit.Assert.*
import org.junit.Test

class ImportPresentationTest {
    @Test fun resultTitlesDistinguishFailuresDuplicatesAndDisconnection() {
        assertEquals("导入失败", ImportProgress(2, failed = 2, phase = ImportPhase.FINISHED).title)
        assertEquals("部分导入完成", ImportProgress(2, succeeded = 1, failed = 1, phase = ImportPhase.FINISHED).title)
        assertEquals("文件均已存在", ImportProgress(2, skipped = 2, phase = ImportPhase.FINISHED).title)
        assertEquals("已取消导入", ImportProgress(2, phase = ImportPhase.CANCELLED).title)
        assertEquals("导入已停止", ImportProgress(2, phase = ImportPhase.CANCELLED, stopReason = "相机已断开").title)
    }

    @Test fun completingBatchKeepsFailuresAndSelectionsMadeDuringImport() {
        val selected = SelectionState(true, setOf("saved", "failed", "new", "reselected"))
        val result = selected.afterImport(setOf("saved", "reselected"), setOf("saved", "failed"))
        assertEquals(setOf("failed", "new", "reselected"), result.ids)
        assertTrue(result.active)
    }

    @Test fun completingAllSelectedFilesReturnsToBrowsing() {
        val result = SelectionState(true, setOf("saved", "duplicate"))
            .afterImport(setOf("saved", "duplicate"), setOf("saved", "duplicate"))
        assertTrue(result.ids.isEmpty())
        assertFalse(result.active)
    }
}
