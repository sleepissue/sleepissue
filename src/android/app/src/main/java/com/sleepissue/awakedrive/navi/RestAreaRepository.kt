package com.sleepissue.awakedrive.navi

import android.content.Context
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaCsv
import java.io.InputStream

/**
 * 앱에 내장한 졸음쉼터·휴게소 표를 읽는다 (REQ_RST_001). 네트워크를 쓰지 않는다.
 *
 * 표 원본은 `data/rest_areas/processed/rest_areas.csv`이고, app/build.gradle.kts가 그 폴더를
 * assets로 묶는다(복사본을 따로 두지 않음). 표를 바꾸려면 `src/tools-py/preprocess_rest_areas.py`를 다시 돌린다.
 *
 * 처음 [areas]를 읽을 때 한 번만 파싱한다(468줄, 수 ms). 표가 깨졌으면 그때 예외가 나므로
 * 출시 전 `RestAreaRepositoryTest`가 같은 파일로 먼저 잡는다.
 */
class RestAreaRepository(private val openTable: () -> InputStream) {

    constructor(context: Context) : this({ context.applicationContext.assets.open(ASSET_NAME) })

    val areas: List<RestArea> by lazy {
        openTable().bufferedReader(Charsets.UTF_8).use { RestAreaCsv.parse(it.readText()) }
    }

    private val byId: Map<String, RestArea> by lazy { areas.associateBy { it.id } }

    /** 휴식 인증(MSG-09)·보상(MSG-10)의 restAreaId로 쉼터를 찾는다. */
    fun find(id: String): RestArea? = byId[id]

    companion object {
        const val ASSET_NAME = "rest_areas.csv"
    }
}
