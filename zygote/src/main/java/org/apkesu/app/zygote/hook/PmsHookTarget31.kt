package org.apkesu.app.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import org.apkesu.app.zygote.util.Logcat.logI
import org.apkesu.app.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import org.apkesu.app.zygote.util.ServiceUtils.getPackagesForUidLocal
import org.apkesu.app.zygote.util.ZLUtils.getArgument
import org.apkesu.app.zygote.util.ZygoteConstants.APPS_FILTER_CLASS
import org.apkesu.app.zygote.util.ZygoteConstants.PMS_COMPUTER_TRACKER_CLASS

@RequiresApi(Build.VERSION_CODES.S)
class PmsHookTarget31 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget31"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                PMS_COMPUTER_TRACKER_CLASS,
                "getPackageSetting",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { Binder.getCallingUid() },
                    { frame.getArgument(1) as? String },
                    ::getPackagesForUidLocal,
                    null,
                )
            }

            hookBefore(
                PMS_COMPUTER_TRACKER_CLASS,
                "getPackageSettingInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(2) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getPackagesForUidLocal,
                    null,
                )
            }

            hookAfter(
                PMS_COMPUTER_TRACKER_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(4) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getPackagesForUidLocal,
                    null,
                )
            }

            hookAfter(
                PMS_COMPUTER_TRACKER_CLASS,
                "getApplicationInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(3) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getPackagesForUidLocal,
                    null,
                )
            }

            hookBefore(
                APPS_FILTER_CLASS,
                "shouldFilterApplication",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(1) as? Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(3)) },
                    ::getPackagesForUidLocal,
                    true,
                )
            }
        }
    }
}
