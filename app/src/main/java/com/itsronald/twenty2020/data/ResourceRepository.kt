package com.itsronald.twenty2020.data

import android.support.annotation.ColorRes
import android.support.annotation.StringRes

/**
 * An abstraction over the access of resources through the app.
 */
interface ResourceRepository {

    /**
     * Returns a localized formatted string from the application's package's default string table,
     * substituting the format arguments as defined in Formatter and format(String, Object...).
     *
     * @param resId Resource id for the format string
     * @param formatArgs The format arguments that will be used for substitution. By default, no
     * arguments are provided.
     *
     * @return The string data associated with the resource, formatted and stripped of styled text information.
     */
    fun getString(@StringRes resId: Int, vararg formatArgs: Any = emptyArray()): String

    /**
     * Returns a color associated with a particular resource ID.
     * Starting in Build.VERSION_CODES.M, the returned color will be styled for the specified Context's theme.
     *
     * @param resId The desired resource identifier, as generated by the aapt tool. This integer
     * encodes the package, type, and resource entry. The value 0 is an invalid identifier.
     *
     * @return A single color value in the form 0xAARRGGBB.
     *
     */
    fun getColor(@ColorRes resId: Int): Int

    /**
     * Notify the resource repository that resources are available to be backed up.
     */
    fun notifyBackupDataChanged()

    /**
     * Retrieve a persisted preference String stored under a String key with ID [keyResId]
     *
     * @param keyResId The resource ID of the key used to store the preference.
     * @param prefsFilename If provided, a filename for the preferences where the requested
     * preference is stored. Otherwise, the default preferences are queried.
     *
     * @return The persisted String value for [keyResId], or null if it does not exist.
     */
    fun getPreferenceString(@StringRes keyResId: Int, prefsFilename: String? = null): String?

    /**
     * Save a String under a key with ID [keyResId]
     *
     * @param keyResId The resource ID of the key used to store the preference.
     * @param stringToSave The String to save under the key with ID [keyResId].
     * @param prefsFilename If provided, a filename for the preferences where the preference should
     * be stored. Otherwise, the default preferences are used.
     */
    fun savePreferenceString(@StringRes keyResId: Int, stringToSave: String, prefsFilename: String? = null)
}