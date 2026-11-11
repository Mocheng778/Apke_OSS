package org.apkesu.app.zygote.hook

import org.apkesu.app.zygote.util.Logcat.logI
import org.apkesu.app.zygote.util.ServiceUtils.getPackagesForUidLocal
import org.apkesu.app.zygote.util.ZLUtils.getArgument
import org.apkesu.app.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

abstract class PmsHookTarget29N30 : PmsHookTargetBase() {

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookAfter(
                PACKAGE_MANAGER_SERVICE_CLASS,
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
                PACKAGE_MANAGER_SERVICE_CLASS,
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
        }
    }
}
