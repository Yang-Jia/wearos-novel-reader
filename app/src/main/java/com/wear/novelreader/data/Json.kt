package com.wear.novelreader.data

import org.json.JSONArray
import org.json.JSONObject

object Json {

    fun chapterToJson(chapter: Chapter): JSONObject = JSONObject()
        .put("title", chapter.title)
        .put("file", chapter.file)
        .put("chars", chapter.chars)

    fun chapterFromJson(json: JSONObject): Chapter = Chapter(
        title = json.optString("title", "未命名"),
        file = json.getString("file"),
        chars = json.optInt("chars", 0)
    )

    fun metaToJson(meta: BookMeta): JSONObject {
        val chapters = JSONArray()
        meta.chapters.forEach { chapters.put(chapterToJson(it)) }
        return JSONObject()
            .put("id", meta.id)
            .put("title", meta.title)
            .put("author", meta.author)
            .put("format", meta.format)
            .put("addedAt", meta.addedAt)
            .put("chapters", chapters)
    }

    fun metaFromJson(text: String): BookMeta {
        val json = JSONObject(text)
        val array = json.optJSONArray("chapters") ?: JSONArray()
        val chapters = ArrayList<Chapter>(array.length())
        for (i in 0 until array.length()) {
            chapters.add(chapterFromJson(array.getJSONObject(i)))
        }
        return BookMeta(
            id = json.getString("id"),
            title = json.optString("title", "未命名"),
            author = json.optString("author", ""),
            format = json.optString("format", "txt"),
            addedAt = json.optLong("addedAt", 0L),
            chapters = chapters
        )
    }

    fun progressToJson(progress: BookProgress): JSONObject = JSONObject()
        .put("chapterIndex", progress.chapterIndex)
        .put("charOffset", progress.charOffset)
        .put("updatedAt", progress.updatedAt)

    fun progressFromJson(text: String): BookProgress {
        val json = JSONObject(text)
        return BookProgress(
            chapterIndex = json.optInt("chapterIndex", 0),
            charOffset = json.optInt("charOffset", 0),
            updatedAt = json.optLong("updatedAt", 0L)
        )
    }
}
