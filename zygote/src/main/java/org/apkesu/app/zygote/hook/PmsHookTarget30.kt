package org.apkesu.app.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import org.apkesu.app.zygote.util.Logcat.logI
import org.apkesu.app.zygote.util.ServiceUtils.getCallingApps
import org.apkesu.app.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import org.apkesu.app.zygote.util.ZLUtils.getArgument
import org.apkesu.app.zygote.util.ZygoteConstants.APPS_FILTER_CLASS
import org.apkesu.app.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

@RequiresApi(Build.VERSION_CODES.R)
class PmsHookTarget30 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget30"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getPackageSetting",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { Binder.getCallingUid() },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
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
                    { frame.getArgument(1) as Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(3)) },
                    ::getCallingApps,
                    true,
                )
            }

            hookAfter(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(4) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
                    null,
                )
            }

            hookAfter(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getApplicationInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(3) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
                    null,
                )
            }
        }
    }
}
