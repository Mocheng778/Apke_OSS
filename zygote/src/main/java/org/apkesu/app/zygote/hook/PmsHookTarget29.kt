package org.apkesu.app.zygote.hook

import org.apkesu.app.zygote.util.Logcat.logI
import org.apkesu.app.zygote.util.ServiceUtils.getCallingApps
import org.apkesu.app.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import org.apkesu.app.zygote.util.ZLUtils.getArgument
import org.apkesu.app.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

class PmsHookTarget29 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget29"

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                service.pms::class.java.name,
                "filterAppAccessLPr",
                argumentCount = 5,
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(2) as? Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(1)) },
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
