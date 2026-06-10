package com.exio.comicreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一个漫画库目录（用户通过 OpenDocumentTree 授权的文件夹） */
@Entity(
    tableName = "library_folders",
    indices = [Index(value = ["treeUri"], unique = true)],
)
data class LibraryFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,       // 目录树 Uri：一次授权覆盖整棵子树
    val displayName: String,   // 目录名，列表展示用
    val addedAt: Long,
)
