package com.itsronald.twenty2020.settings

import android.Manifest
import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatDelegate
import com.f2prateek.rx.preferences.RxSharedPreferences
import com.itsronald.twenty2020.R
import com.itsronald.twenty2020.data.ResourceRepository
import com.itsronald.twenty2020.settings.injection.SettingsComponent
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import com.karumi.dexter.listener.single.SnackbarOnDeniedPermissionListener
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.lang.kotlin.onError
import rx.lang.kotlin.plusAssign
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import timber.log.Timber
import javax.inject.Inject


class SettingsPresenter
    @Inject constructor(override var view: SettingsContract.SettingsView,
                        val resources: ResourceRepository,
                        val preferences: RxSharedPreferences)
    : SettingsContract.Presenter {

    override lateinit var settingsComponent: SettingsComponent

    //region Observers

    /** Subscriptions maintained by this presenter. */
    private lateinit var subscriptions: CompositeSubscription

    /**
     * Observe changes to the display_night_mode SharedPreference.
     * The original value (the current setting when the Observer is started) is not sent to
     * subscribers.
     *
     * @return A new Observable that reacts to changes to the display_night_mode setting.
     */
    private fun nightModePreference(): Observable<Int> = preferences
            .getString(resources.getString(R.string.pref_key_display_night_mode))
            .asObservable()
            .map { it.toInt() }
            .filter {
                AppCompatDelegate.getDefaultNightMode() != it
                        && (it == AppCompatDelegate.MODE_NIGHT_NO
                        ||  it == AppCompatDelegate.MODE_NIGHT_YES
                        ||  it == AppCompatDelegate.MODE_NIGHT_AUTO)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .onError { Timber.e(it, "Unable to observe night mode setting.") }

    /**
     * Observe changes to the display_location_based_night_mode SharedPreference.
     * The original value (the current setting when the Observer is started) is not sent to
     * subscribers.
     *
     * @return A new Observable that reacts to changes to the display_location_based_night_mode
     * setting.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private fun nightModeLocationPreference(): Observable<Boolean> = preferences
            .getBoolean(resources.getString(R.string.pref_key_display_location_based_night_mode))
            .asObservable()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .onError { Timber.e(it, "Unable to observe night mode location setting.") }

    //endregion

    //region Activity lifecycle

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // No need to opt-into locations runtime permission; it is granted by default.
            Timber.i("Removing preference display_location_based_night_mode: not needed before Android Marshmallow.")
            view.removePreference(
                    prefKeyID = R.string.pref_key_display_location_based_night_mode,
                    inCategory = R.string.pref_key_category_display
            )
        } else {
            // Runtime permissions are in effect. Continue any ongoing requests.
            Dexter.continuePendingRequestIfPossible(permissionListener)
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.v("SettingsPresenter created.")

        startSubscriptions()
    }

    override fun onStop() {
        super.onStop()
        Timber.v("SettingsPresenter is stopping.")
        subscriptions.unsubscribe()

        // Notify the Android Backup API that preferences should be backed up.
        Timber.v("Notifying Android Backup API that data has changed.")
        resources.notifyBackupDataChanged()
    }

    //endregion

    private fun startSubscriptions() {
        Timber.i("Starting subscriptions.")
        subscriptions = CompositeSubscription()

        subscriptions += nightModePreference().subscribe { setNewNightMode(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Below API 23, this option is hidden from the user.
            Timber.v("Subscribed to nightModeLocationPreference.")
            subscriptions += nightModeLocationPreference().subscribe { enabled ->
                Timber.v("Night mode location preference is enabled: $enabled")
                if (enabled) ensureLocationPermission()
            }
        }
    }

    /**
     * Set a new default night mode.
     * If the supplied [nightMode] is already the default, this will have no effect.
     *
     * @param nightMode The new night mode value to set as the default.
     */
    private fun setNewNightMode(@AppCompatDelegate.NightMode nightMode: Int) {
        val nightModeName = when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_AUTO -> "MODE_NIGHT_AUTO"
            AppCompatDelegate.MODE_NIGHT_NO -> "MODE_NIGHT_NO"
            AppCompatDelegate.MODE_NIGHT_YES -> "MODE_NIGHT_YES"
            else -> "UNKNOWN"
        }

        if (AppCompatDelegate.getDefaultNightMode() == nightMode) {
            Timber.v("Ignoring setNewNightMode($nightModeName): NightMode is already $nightModeName.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            refreshNightModeLocationPreference(nightMode)
        }

        Timber.i("Night mode changed to $nightModeName")
        AppCompatDelegate.setDefaultNightMode(nightMode)
        view.refreshNightMode(nightMode)
    }

    //region Location-based night mode (API 23+ only)

    /**
     * Enable or disable the preference for display_location_based_night_mode based on the current
     * night mode setting. The preference will be enabled iff the night mode is MODE_NIGHT_AUTO.
     *
     * This method should only be called if runtime permissions are enabled. Otherwise, the
     * preference is not exposed to the user at all.
     *
     * @param nightMode The current night mode.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private fun refreshNightModeLocationPreference(@AppCompatDelegate.NightMode nightMode: Int) {
        val enabled = nightMode == AppCompatDelegate.MODE_NIGHT_AUTO
        Timber.v("Setting preference display_location_based_night_mode enabled to $enabled.")
        view.setPreferenceEnabled(
                prefKeyID = R.string.pref_key_display_location_based_night_mode,
                enabled = enabled
        )
    }

    /**
     * If a location permissions request is not already in progress, request the
     * ACCESS_COARSE_LOCATION permission from the user.
     *
     * If the request is denied, display a Snackbar via Dexter.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private fun ensureLocationPermission() {
        if (Dexter.isRequestOngoing()) {
            Timber.v("Permissions request is already occurring. Skipping duplicate request.")
            return
        }

        Timber.v("Requesting permission ${Manifest.permission.ACCESS_COARSE_LOCATION}.")
        Dexter.checkPermission(permissionListener,
                Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * A lazily instantiated listener for permissions requests.
     */
    private val permissionListener: PermissionListener by lazy {
        val baseSnackbarPermissionListener = SnackbarOnDeniedPermissionListener.Builder
                .with(view.contentView, R.string.location_permission_rationale)
                .withOpenSettingsButton(R.string.settings)
                .withCallback(object : Snackbar.Callback() {
                    override fun onShown(snackbar: Snackbar?) {
                        super.onShown(snackbar)
                        // If the Snackbar is shown, the permission was denied.
                        // Un-check the setting that requires the permission.
                        Timber.w("Permission request was denied. Disabling automatic night mode.")
                        view.setPreferenceChecked(
                                prefKeyID = R.string.pref_key_display_location_based_night_mode,
                                checked = false
                        )
                    }
                })
                .build()
        SnackbarPermissionListener(baseListener = baseSnackbarPermissionListener)
    }

    /**
     * A subclass of [SnackbarOnDeniedPermissionListener] that additionally implements
     * [onPermissionRationaleShouldBeShown]. Since it responds to a user action, it assumes that
     * the permission is not permanently denied so as to alert the user that the setting cannot be
     * set.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private class SnackbarPermissionListener(baseListener: PermissionListener) :
            PermissionListener by baseListener {

        override fun onPermissionRationaleShouldBeShown(permission: PermissionRequest,
                                                        token: PermissionToken) {
            token.cancelPermissionRequest()

            val reconstructedRequest = PermissionRequest(permission.name)
            val permanentlyDenied = false
            onPermissionDenied(PermissionDeniedResponse(reconstructedRequest, permanentlyDenied))
        }
    }

    //endregion
}