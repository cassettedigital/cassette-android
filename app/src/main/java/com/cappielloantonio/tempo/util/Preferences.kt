package com.cappielloantonio.tempo.util

import android.util.Log
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.model.HomeSector
import com.cappielloantonio.tempo.subsonic.models.OpenSubsonicExtension
import com.google.gson.Gson


object Preferences {
    const val THEME = "theme"
    private const val SERVER = "server"
    private const val USER = "user"
    private const val PASSWORD = "password"
    private const val TOKEN = "token"
    private const val SALT = "salt"
    private const val LOW_SECURITY = "low_security"
    private const val BATTERY_OPTIMIZATION = "battery_optimization"
    private const val SERVER_ID = "server_id"
    private const val OPEN_SUBSONIC = "open_subsonic"
    private const val OPEN_SUBSONIC_EXTENSIONS = "open_subsonic_extensions"
    private const val LOCAL_ADDRESS = "local_address"
    private const val IN_USE_SERVER_ADDRESS = "in_use_server_address"
    private const val NEXT_SERVER_SWITCH = "next_server_switch"
    private const val PLAYBACK_SPEED = "playback_speed"
    private const val SKIP_SILENCE = "skip_silence"
    private const val IMAGE_CACHE_SIZE = "image_cache_size"
    private const val STREAMING_CACHE_SIZE = "streaming_cache_size"
    private const val IMAGE_SIZE = "image_size"
    private const val MAX_BITRATE_WIFI = "max_bitrate_wifi"
    private const val MAX_BITRATE_MOBILE = "max_bitrate_mobile"
    private const val AUDIO_TRANSCODE_FORMAT_WIFI = "audio_transcode_format_wifi"
    private const val AUDIO_TRANSCODE_FORMAT_MOBILE = "audio_transcode_format_mobile"
    private const val WIFI_ONLY = "wifi_only"
    private const val DATA_SAVING_MODE = "data_saving_mode"
    private const val SERVER_UNREACHABLE = "server_unreachable"
    private const val SYNC_STARRED_TRACKS_FOR_OFFLINE_USE = "sync_starred_tracks_for_offline_use"
    private const val QUEUE_SYNCING = "queue_syncing"
    private const val QUEUE_SYNCING_COUNTDOWN = "queue_syncing_countdown"
    private const val ROUNDED_CORNER = "rounded_corner"
    private const val ROUNDED_CORNER_SIZE = "rounded_corner_size"
    private const val PODCAST_SECTION_VISIBILITY = "podcast_section_visibility"
    private const val RADIO_SECTION_VISIBILITY = "radio_section_visibility"
    private const val MUSIC_DIRECTORY_SECTION_VISIBILITY = "music_directory_section_visibility"
    private const val REPLAY_GAIN_MODE = "replay_gain_mode"
    private const val AUDIO_TRANSCODE_PRIORITY = "audio_transcode_priority"
    private const val STREAMING_CACHE_STORAGE = "streaming_cache_storage"
    private const val DOWNLOAD_STORAGE = "download_storage"
    private const val DEFAULT_DOWNLOAD_VIEW_TYPE = "default_download_view_type"
    private const val AUDIO_TRANSCODE_DOWNLOAD = "audio_transcode_download"
    private const val AUDIO_TRANSCODE_DOWNLOAD_PRIORITY = "audio_transcode_download_priority"
    private const val MAX_BITRATE_DOWNLOAD = "max_bitrate_download"
    private const val AUDIO_TRANSCODE_FORMAT_DOWNLOAD = "audio_transcode_format_download"
    private const val SHARE = "share"
    private const val SCROBBLING = "scrobbling"
    private const val ESTIMATE_CONTENT_LENGTH = "estimate_content_length"
    private const val BUFFERING_STRATEGY = "buffering_strategy"
    private const val SKIP_MIN_STAR_RATING = "skip_min_star_rating"
    private const val MIN_STAR_RATING = "min_star_rating"
    private const val ALWAYS_ON_DISPLAY = "always_on_display"
    private const val AUDIO_QUALITY_PER_ITEM = "audio_quality_per_item"
    private const val HOME_SECTOR_LIST = "home_sector_list"
    private const val RATING_PER_ITEM = "rating_per_item"
    private const val NEXT_UPDATE_CHECK = "next_update_check"
    private const val CONTINUOUS_PLAY = "continuous_play"
    private const val LAST_INSTANT_MIX = "last_instant_mix"


    @JvmStatic
    fun getServer(): String? {
        return App.getInstance().preferences.getString(SERVER, null)
    }

    @JvmStatic
    fun setServer(server: String?) {
        App.getInstance().preferences.edit().putString(SERVER, server).apply()
    }

    @JvmStatic
    fun getUser(): String? {
        return App.getInstance().preferences.getString(USER, null)
    }

    @JvmStatic
    fun setUser(user: String?) {
        App.getInstance().preferences.edit().putString(USER, user).apply()
    }

    @JvmStatic
    fun getPassword(): String? {
        return App.getInstance().preferences.getString(PASSWORD, null)
    }

    @JvmStatic
    fun setPassword(password: String?) {
        App.getInstance().preferences.edit().putString(PASSWORD, password).apply()
    }

    @JvmStatic
    fun getToken(): String? {
        return App.getInstance().preferences.getString(TOKEN, null)
    }

    @JvmStatic
    fun setToken(token: String?) {
        App.getInstance().preferences.edit().putString(TOKEN, token).apply()
    }

    @JvmStatic
    fun getSalt(): String? {
        return App.getInstance().preferences.getString(SALT, null)
    }

    @JvmStatic
    fun setSalt(salt: String?) {
        App.getInstance().preferences.edit().putString(SALT, salt).apply()
    }

    @JvmStatic
    fun isLowScurity(): Boolean {
        return App.getInstance().preferences.getBoolean(LOW_SECURITY, false)
    }

    @JvmStatic
    fun setLowSecurity(isLowSecurity: Boolean) {
        App.getInstance().preferences.edit().putBoolean(LOW_SECURITY, isLowSecurity).apply()
    }

    @JvmStatic
    fun getServerId(): String? {
        return App.getInstance().preferences.getString(SERVER_ID, null)
    }

    @JvmStatic
    fun setServerId(serverId: String?) {
        App.getInstance().preferences.edit().putString(SERVER_ID, serverId).apply()
    }

    @JvmStatic
    fun isOpenSubsonic(): Boolean {
        return App.getInstance().preferences.getBoolean(OPEN_SUBSONIC, false)
    }

    @JvmStatic
    fun setOpenSubsonic(isOpenSubsonic: Boolean) {
        App.getInstance().preferences.edit().putBoolean(OPEN_SUBSONIC, isOpenSubsonic).apply()
    }

    @JvmStatic
    fun getOpenSubsonicExtensions(): String? {
        return App.getInstance().preferences.getString(OPEN_SUBSONIC_EXTENSIONS, null)
    }

    @JvmStatic
    fun setOpenSubsonicExtensions(extension: List<OpenSubsonicExtension>) {
        App.getInstance().preferences.edit().putString(OPEN_SUBSONIC_EXTENSIONS, Gson().toJson(extension)).apply()
    }

    @JvmStatic
    fun getLocalAddress(): String? {
        return App.getInstance().preferences.getString(LOCAL_ADDRESS, null)
    }

    @JvmStatic
    fun setLocalAddress(address: String?) {
        App.getInstance().preferences.edit().putString(LOCAL_ADDRESS, address).apply()
    }

    @JvmStatic
    fun getInUseServerAddress(): String? {
        return App.getInstance().preferences.getString(IN_USE_SERVER_ADDRESS, null)
            ?.takeIf { it.isNotBlank() }
            ?: getServer()
    }

    @JvmStatic
    fun isInUseServerAddressLocal(): Boolean {
        return getInUseServerAddress() == getLocalAddress()
    }

    @JvmStatic
    fun switchInUseServerAddress() {
        val inUseAddress = if (getInUseServerAddress() == getServer()) getLocalAddress() else getServer()
        App.getInstance().preferences.edit().putString(IN_USE_SERVER_ADDRESS, inUseAddress).apply()
    }

    @JvmStatic
    fun isServerSwitchable(): Boolean {
        return App.getInstance().preferences.getLong(
                NEXT_SERVER_SWITCH, 0
        ) + 15000 < System.currentTimeMillis() && !getServer().isNullOrEmpty() && !getLocalAddress().isNullOrEmpty()
    }

    @JvmStatic
    fun setServerSwitchableTimer() {
        App.getInstance().preferences.edit().putLong(NEXT_SERVER_SWITCH, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun askForOptimization(): Boolean {
        return App.getInstance().preferences.getBoolean(BATTERY_OPTIMIZATION, true)
    }

    @JvmStatic
    fun dontAskForOptimization() {
        App.getInstance().preferences.edit().putBoolean(BATTERY_OPTIMIZATION, false).apply()
    }

    @JvmStatic
    fun getPlaybackSpeed(): Float {
        return App.getInstance().preferences.getFloat(PLAYBACK_SPEED, 1f)
    }

    @JvmStatic
    fun setPlaybackSpeed(playbackSpeed: Float) {
        App.getInstance().preferences.edit().putFloat(PLAYBACK_SPEED, playbackSpeed).apply()
    }

    @JvmStatic
    fun isSkipSilenceMode(): Boolean {
        return App.getInstance().preferences.getBoolean(SKIP_SILENCE, false)
    }

    @JvmStatic
    fun setSkipSilenceMode(isSkipSilenceMode: Boolean) {
        App.getInstance().preferences.edit().putBoolean(SKIP_SILENCE, isSkipSilenceMode).apply()
    }

    @JvmStatic
    fun getImageCacheSize(): Int {
        return App.getInstance().preferences.getString(IMAGE_CACHE_SIZE, "500")!!.toInt()
    }

    @JvmStatic
    fun getImageSize(): Int {
        return App.getInstance().preferences.getString(IMAGE_SIZE, "-1")!!.toInt()
    }

    @JvmStatic
    fun getStreamingCacheSize(): Long {
        return App.getInstance().preferences.getString(STREAMING_CACHE_SIZE, "256")!!.toLong()
    }

    @JvmStatic
    fun getMaxBitrateWifi(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_WIFI, "0")!!
    }

    @JvmStatic
    fun getMaxBitrateMobile(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_MOBILE, "0")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatWifi(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_WIFI, "raw")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatMobile(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_MOBILE, "raw")!!
    }

    @JvmStatic
    fun isWifiOnly(): Boolean {
        return App.getInstance().preferences.getBoolean(WIFI_ONLY, false)
    }

    @JvmStatic
    fun isDataSavingMode(): Boolean {
        return App.getInstance().preferences.getBoolean(DATA_SAVING_MODE, false)
    }

    @JvmStatic
    fun setDataSavingMode(isDataSavingModeEnabled: Boolean) {
        App.getInstance().preferences.edit().putBoolean(DATA_SAVING_MODE, isDataSavingModeEnabled)
                .apply()
    }

    @JvmStatic
    fun isStarredSyncEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SYNC_STARRED_TRACKS_FOR_OFFLINE_USE, false)
    }

    @JvmStatic
    fun setStarredSyncEnabled(isStarredSyncEnabled: Boolean) {
        App.getInstance().preferences.edit().putBoolean(
                SYNC_STARRED_TRACKS_FOR_OFFLINE_USE, isStarredSyncEnabled
        ).apply()
    }

    @JvmStatic
    fun showServerUnreachableDialog(): Boolean {
        return App.getInstance().preferences.getLong(
                SERVER_UNREACHABLE, 0
        ) + 86400000 < System.currentTimeMillis()
    }

    @JvmStatic
    fun setServerUnreachableDatetime() {
        App.getInstance().preferences.edit().putLong(SERVER_UNREACHABLE, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isSyncronizationEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(QUEUE_SYNCING, false)
    }

    @JvmStatic
    fun getSyncCountdownTimer(): Int {
        return App.getInstance().preferences.getString(QUEUE_SYNCING_COUNTDOWN, "5")!!.toInt()
    }

    @JvmStatic
    fun isCornerRoundingEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(ROUNDED_CORNER, false)
    }

    @JvmStatic
    fun getRoundedCornerSize(): Int {
        return App.getInstance().preferences.getString(ROUNDED_CORNER_SIZE, "12")!!.toInt()
    }

    @JvmStatic
    fun isPodcastSectionVisible(): Boolean {
        // cassette: podcasts are not a Cassette surface (iOS hides them too) —
        // default OFF; the setting can re-enable for self-hosters.
        return App.getInstance().preferences.getBoolean(PODCAST_SECTION_VISIBILITY, false)
    }

    @JvmStatic
    fun setPodcastSectionHidden() {
        App.getInstance().preferences.edit().putBoolean(PODCAST_SECTION_VISIBILITY, false).apply()
    }

    @JvmStatic
    fun isRadioSectionVisible(): Boolean {
        // cassette: radio hidden by default (iOS parity).
        return App.getInstance().preferences.getBoolean(RADIO_SECTION_VISIBILITY, false)
    }

    @JvmStatic
    fun setRadioSectionHidden() {
        App.getInstance().preferences.edit().putBoolean(RADIO_SECTION_VISIBILITY, false).apply()
    }

    @JvmStatic
    fun getCassetteBearerToken(): String? {
        // cassette: the pairing bearer token — lets the app re-read
        // /api/player/me (skin changes, hub address drift) after pairing.
        return App.getInstance().preferences.getString("cassette_bearer_token", null)
    }

    @JvmStatic
    fun setCassetteBearerToken(token: String?) {
        App.getInstance().preferences.edit().putString("cassette_bearer_token", token).apply()
    }

    @JvmStatic
    fun getCassetteCloudPlayHighwater(): Long {
        // cassette play spine: the max chronology.timestamp already POSTed to
        // /api/sync/plays. Rows newer than this are sent on the next flush.
        return App.getInstance().preferences.getLong("cassette_cloud_play_highwater", 0L)
    }

    @JvmStatic
    fun setCassetteCloudPlayHighwater(value: Long) {
        App.getInstance().preferences.edit().putLong("cassette_cloud_play_highwater", value).apply()
    }

    @JvmStatic
    fun getCassetteCloudPlayReadHighwater(): Long {
        // cassette play spine (Phase B): the max played_at (epoch ms) already
        // pulled from /api/sync/plays. The next pull asks for plays newer than this.
        return App.getInstance().preferences.getLong("cassette_cloud_play_read_highwater", 0L)
    }

    @JvmStatic
    fun setCassetteCloudPlayReadHighwater(value: Long) {
        App.getInstance().preferences.edit().putLong("cassette_cloud_play_read_highwater", value).apply()
    }

    @JvmStatic
    fun getCassetteThemeId(): String {
        // cassette: the account's cross-device skin (users.theme_id).
        return App.getInstance().preferences.getString("cassette_theme_id", "classic")!!
    }

    @JvmStatic
    fun setCassetteThemeId(themeId: String?) {
        App.getInstance().preferences.edit().putString("cassette_theme_id", themeId ?: "classic").apply()
    }

    @JvmStatic
    fun getCassetteDownloadQuality(): String {
        // cassette: account download tier (users.download_quality_tier via
        // /api/sync/account). "high" mirrors the cloud's column default and
        // iOS's fallback when the field is absent.
        return App.getInstance().preferences.getString("cassette_download_quality", "high")!!
    }

    @JvmStatic
    fun setCassetteDownloadQuality(quality: String?) {
        App.getInstance().preferences.edit().putString("cassette_download_quality", quality ?: "high").apply()
    }

    @JvmStatic
    fun isServerMode(): Boolean {
        // cassette: Server Mode is account-sourced + web-authoritative (mirrors
        // iOS cassette.serverMode — read-only on the phone, no toggle). The
        // desktop owns the write path; the engine reads it from /api/sync/account.
        // false (default, and when absent) = offline-first / on-device-only:
        // browse shows only synced music and non-downloaded tracks never stream.
        // true = the account opted in: full-library browse + streaming return.
        return App.getInstance().preferences.getBoolean("cassette_server_mode", false)
    }

    @JvmStatic
    fun setServerMode(serverMode: Boolean) {
        App.getInstance().preferences.edit().putBoolean("cassette_server_mode", serverMode).apply()
    }

    @JvmStatic
    fun isOfflineFirst(): Boolean = !isServerMode()

    @JvmStatic
    fun getCassetteDeviceId(): String {
        // cassette: this phone's identity in the account's device registry
        // (iOS uses identifierForVendor). Minted once, never re-keyed — the
        // registry survives re-pairs the same way the Server row does.
        var id = App.getInstance().preferences.getString("cassette_device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            App.getInstance().preferences.edit().putString("cassette_device_id", id).apply()
        }
        return id
    }

    @JvmStatic
    fun getCassetteInstallId(): String {
        // cassette diagnostics: an anonymous, install-scoped UUID that correlates
        // crash/feedback reports for THIS install (and is the server's rate-limit
        // key). Minted once, never re-keyed. Distinct from cassette_device_id (the
        // account registry identity): install_id is correlation only, NOT identity,
        // and survives independently of pairing.
        var id = App.getInstance().preferences.getString("cassette_install_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            App.getInstance().preferences.edit().putString("cassette_install_id", id).apply()
        }
        return id
    }

    @JvmStatic
    fun getCassetteDiagnosticsExitHighwater(): Long {
        // cassette diagnostics: the newest ApplicationExitInfo timestamp (epoch ms)
        // already harvested into a report. Exits at or before this are skipped, so
        // the same native crash / ANR is never reported twice.
        return App.getInstance().preferences.getLong("cassette_diagnostics_exit_highwater", 0L)
    }

    @JvmStatic
    fun setCassetteDiagnosticsExitHighwater(value: Long) {
        App.getInstance().preferences.edit().putLong("cassette_diagnostics_exit_highwater", value).apply()
    }

    @JvmStatic
    fun getLibraryLastCategory(): String {
        // cassette: Library reopens on the last category viewed (iOS pattern).
        return App.getInstance().preferences.getString("library_last_category", "albums")!!
    }

    @JvmStatic
    fun setLibraryLastCategory(category: String) {
        App.getInstance().preferences.edit().putString("library_last_category", category).apply()
    }

    @JvmStatic
    fun isMusicDirectorySectionVisible(): Boolean {
        // cassette: directory browsing hidden (iOS hides directories too);
        // the setting can re-enable for self-hosters.
        return App.getInstance().preferences.getBoolean(MUSIC_DIRECTORY_SECTION_VISIBILITY, false)
    }

    @JvmStatic
    fun getReplayGainMode(): String? {
        return App.getInstance().preferences.getString(REPLAY_GAIN_MODE, "disabled")
    }

    @JvmStatic
    fun isServerPrioritized(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_PRIORITY, false)
    }

    @JvmStatic
    fun getStreamingCacheStoragePreference(): Int {
        return App.getInstance().preferences.getString(STREAMING_CACHE_STORAGE, "0")!!.toInt()
    }

    @JvmStatic
    fun setStreamingCacheStoragePreference(streamingCachePreference: Int) {
        return App.getInstance().preferences.edit().putString(
                STREAMING_CACHE_STORAGE,
                streamingCachePreference.toString()
        ).apply()
    }

    @JvmStatic
    fun getDownloadStoragePreference(): Int {
        return App.getInstance().preferences.getString(DOWNLOAD_STORAGE, "0")!!.toInt()
    }

    @JvmStatic
    fun setDownloadStoragePreference(storagePreference: Int) {
        return App.getInstance().preferences.edit().putString(
                DOWNLOAD_STORAGE,
                storagePreference.toString()
        ).apply()
    }

    @JvmStatic
    fun getDefaultDownloadViewType(): String {
        return App.getInstance().preferences.getString(
                DEFAULT_DOWNLOAD_VIEW_TYPE,
                Constants.DOWNLOAD_TYPE_TRACK
        )!!
    }

    @JvmStatic
    fun setDefaultDownloadViewType(viewType: String) {
        return App.getInstance().preferences.edit().putString(
                DEFAULT_DOWNLOAD_VIEW_TYPE,
                viewType
        ).apply()
    }

    @JvmStatic
    fun preferTranscodedDownload(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_DOWNLOAD, false)
    }

    @JvmStatic
    fun isServerPrioritizedInTranscodedDownload(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_DOWNLOAD_PRIORITY, false)
    }

    @JvmStatic
    fun getBitrateTranscodedDownload(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_DOWNLOAD, "0")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatTranscodedDownload(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_DOWNLOAD, "raw")!!
    }

    @JvmStatic
    fun isSharingEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SHARE, false)
    }

    @JvmStatic
    fun isScrobblingEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SCROBBLING, true)
    }

    @JvmStatic
    fun askForEstimateContentLength(): Boolean {
        return App.getInstance().preferences.getBoolean(ESTIMATE_CONTENT_LENGTH, false)
    }

    @JvmStatic
    fun getBufferingStrategy(): Double {
        return App.getInstance().preferences.getString(BUFFERING_STRATEGY, "1")!!.toDouble()
    }

    @JvmStatic
    fun getMinStarRatingAccepted(): Int {
        return App.getInstance().preferences.getInt(MIN_STAR_RATING, 0)
    }

    @JvmStatic
    fun isDisplayAlwaysOn(): Boolean {
        return App.getInstance().preferences.getBoolean(ALWAYS_ON_DISPLAY, false)
    }

    @JvmStatic
    fun showAudioQuality(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_QUALITY_PER_ITEM, false)
    }

    @JvmStatic
    fun getHomeSectorList(): String? {
        return App.getInstance().preferences.getString(HOME_SECTOR_LIST, null)
    }

    @JvmStatic
    fun setHomeSectorList(extension: List<HomeSector>?) {
        App.getInstance().preferences.edit().putString(HOME_SECTOR_LIST, Gson().toJson(extension)).apply()
    }

    @JvmStatic
    fun showItemRating(): Boolean {
        return App.getInstance().preferences.getBoolean(RATING_PER_ITEM, false)
    }

    @JvmStatic
    fun showTempoUpdateDialog(): Boolean {
        return App.getInstance().preferences.getLong(
                NEXT_UPDATE_CHECK, 0
        ) + 86400000 < System.currentTimeMillis()
    }

    @JvmStatic
    fun setTempoUpdateReminder() {
        App.getInstance().preferences.edit().putLong(NEXT_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isContinuousPlayEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(CONTINUOUS_PLAY, true)
    }

    @JvmStatic
    fun setLastInstantMix() {
        App.getInstance().preferences.edit().putLong(LAST_INSTANT_MIX, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isInstantMixUsable(): Boolean {
        return App.getInstance().preferences.getLong(
                LAST_INSTANT_MIX, 0
        ) + 5000 < System.currentTimeMillis()
    }
}