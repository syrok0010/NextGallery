package com.syrok0010.nextgallery.data.memories

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MemoriesDescribeDto(
    val version: String,
    val baseUrl: String,
    val loginFlowUrl: String,
    val uid: String? = null,
)

@Serializable
data class MemoriesConfigDto(
    val version: String,
    @SerialName("vod_disable")
    val vodDisable: Boolean = false,
    @SerialName("video_default_quality")
    val videoDefaultQuality: String? = null,
    @SerialName("places_gis")
    val placesGis: Int? = null,
    @SerialName("systemtags_enabled")
    val systemTagsEnabled: Boolean = false,
    @SerialName("albums_enabled")
    val albumsEnabled: Boolean = false,
    @SerialName("recognize_installed")
    val recognizeInstalled: Boolean = false,
    @SerialName("recognize_enabled")
    val recognizeEnabled: Boolean = false,
    @SerialName("facerecognition_installed")
    val faceRecognitionInstalled: Boolean = false,
    @SerialName("facerecognition_enabled")
    val faceRecognitionEnabled: Boolean = false,
    @SerialName("preview_generator_enabled")
    val previewGeneratorEnabled: Boolean = false,
    @SerialName("timeline_path")
    val timelinePath: String? = null,
    @SerialName("enable_top_memories")
    val enableTopMemories: Boolean = false,
    @SerialName("stack_raw_files")
    val stackRawFiles: Boolean = false,
    @SerialName("dedup_identical")
    val dedupIdentical: Boolean = false,
    @SerialName("show_owner_name_timeline")
    val showOwnerNameTimeline: Boolean = false,
    @SerialName("high_res_cond_default")
    val highResConditionDefault: String? = null,
    @SerialName("livephoto_autoplay")
    val livePhotoAutoplay: Boolean = false,
    @SerialName("livephoto_loop")
    val livePhotoLoop: Boolean = false,
    @SerialName("video_loop")
    val videoLoop: Boolean = false,
    @SerialName("sidebar_filepath")
    val sidebarFilePath: Boolean = false,
    @SerialName("folders_path")
    val foldersPath: String? = null,
    @SerialName("show_hidden_folders")
    val showHiddenFolders: Boolean = false,
    @SerialName("sort_folder_month")
    val sortFolderMonth: Boolean = false,
    @SerialName("sort_album_month")
    val sortAlbumMonth: Boolean = false,
    @SerialName("show_hidden_albums")
    val showHiddenAlbums: Boolean = false,
    @SerialName("album_list_sort")
    val albumListSort: Int? = null,
)

@Serializable
data class MemoriesDayDto(
    val dayid: Int,
    val count: Int,
    val detail: List<MemoriesPhotoDto> = emptyList(),
)

@Serializable
data class MemoriesPhotoDto(
    val fileid: Long,
    val dayid: Int,
    val w: Int? = null,
    val h: Int? = null,
    val etag: String? = null,
    val basename: String? = null,
    val epoch: Long? = null,
    val mimetype: String? = null,
    val liveid: String? = null,
    val auid: String? = null,
    val buid: String? = null,
    @SerialName("shared_by")
    val sharedBy: String? = null,
    @SerialName("isvideo")
    val isVideo: JsonElement? = null,
    @SerialName("video_duration")
    val videoDuration: Long? = null,
    @SerialName("isfavorite")
    val isFavorite: JsonElement? = null,
    @SerialName("ishidden")
    val isHidden: JsonElement? = null,
)
