package io.flutter.plugin.common

/** Minimal JVM-only Flutter API fixture; never included in the Android source set. */
interface BinaryMessenger

class MethodCall(val method: String, val arguments: Any?)

class MethodChannel(val messenger: BinaryMessenger, val name: String) {
    fun interface MethodCallHandler {
        fun onMethodCall(call: MethodCall, result: Result)
    }
    interface Result {
        fun success(result: Any?)
        fun error(errorCode: String, errorMessage: String?, errorDetails: Any?)
        fun notImplemented()
    }
    var handler: MethodCallHandler? = null
        private set

    fun setMethodCallHandler(handler: MethodCallHandler?) {
        this.handler = handler
    }
}
