package com.rootsentinel.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.input.InputManager
import android.os.Build
import android.telephony.TelephonyManager
import android.view.inputmethod.InputMethodManager
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict

/**
 * 硬件指纹（M9）— 5 项
 *
 * 这一层用 Java API 覆盖 native 侧读不到的 sysfs 目录
 * （`/sys/class/sensors`、`/sys/class/power_supply` 的 readdir 常被 SELinux 拒）。
 *
 * 真机的传感器 / 摄像头 / 输入设备数量都很丰富；
 * 模拟器、云手机、容器往往只剩一两个甚至为 0。
 */
class HardwareDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        sensorCount(), camera(), inputDevices(), display(), simState(),
    )

    // ------------------------------------------------------- A.SENSOR_COUNT
    private fun sensorCount(): Evidence = try {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return ev("A.SENSOR_COUNT", Severity.LOW, Verdict.UNKNOWN, "SensorManager 不可用")
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        val n = list.size
        val names = list.take(4).map { it.name ?: "?" }

        when {
            n == 0 -> ev("A.SENSOR_COUNT", Severity.HIGH, Verdict.HIT,
                "没有任何传感器", conf = Confidence.HIGH,
                detail = "真机至少有加速度计 / 光线 / 距离传感器")
            n < 5 -> ev("A.SENSOR_COUNT", Severity.MEDIUM, Verdict.HIT,
                "传感器数量异常少（$n）", vec = listOf("count=$n") + names.map { "s=$it" },
                detail = "真机通常 15+ 个传感器")
            else -> ev("A.SENSOR_COUNT", Severity.INFO, Verdict.PASS,
                "传感器数量正常（$n）", conf = Confidence.HIGH,
                vec = listOf("count=$n") + names.map { "s=$it" })
        }
    } catch (t: Throwable) {
        ev("A.SENSOR_COUNT", Severity.LOW, Verdict.UNKNOWN, "传感器列表不可读")
    }

    // --------------------------------------------------------------- A.CAMERA
    private fun camera(): Evidence = try {
        if (Build.VERSION.SDK_INT < 21) {
            ev("A.CAMERA", Severity.LOW, Verdict.UNKNOWN, "版本过低")
        } else {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ev("A.CAMERA", Severity.LOW, Verdict.UNKNOWN, "CameraManager 不可用")
            val ids = runCatching { cm.cameraIdList }.getOrDefault(emptyArray())
            val n = ids.size
            if (n == 0) {
                ev("A.CAMERA", Severity.HIGH, Verdict.HIT, "没有任何摄像头",
                    conf = Confidence.HIGH,
                    detail = "真机至少有一个后置摄像头")
            } else {
                ev("A.CAMERA", Severity.INFO, Verdict.PASS, "摄像头数量正常（$n）",
                    conf = Confidence.HIGH, vec = listOf("count=$n"))
            }
        }
    } catch (t: Throwable) {
        ev("A.CAMERA", Severity.LOW, Verdict.UNKNOWN, "摄像头列表不可读")
    }

    // --------------------------------------------------------- A.INPUT_DEVICES
    private fun inputDevices(): Evidence = try {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            ?: return ev("A.INPUT_DEVICES", Severity.LOW, Verdict.UNKNOWN, "InputManager 不可用")
        val ids = im.inputDeviceIds
        val n = ids.size
        if (n <= 1) {
            ev("A.INPUT_DEVICES", Severity.MEDIUM, Verdict.HIT,
                "输入设备数量异常少（$n）", vec = listOf("count=$n"),
                detail = "真机有触摸屏、电源键、音量键等多个输入设备",
                fp = "部分虚拟设备 / 云手机会合并输入设备")
        } else {
            ev("A.INPUT_DEVICES", Severity.INFO, Verdict.PASS,
                "输入设备数量正常（$n）", conf = Confidence.HIGH,
                vec = listOf("count=$n"))
        }
    } catch (t: Throwable) {
        ev("A.INPUT_DEVICES", Severity.LOW, Verdict.UNKNOWN, "输入设备不可读")
    }

    // -------------------------------------------------------------- A.DISPLAY
    private fun display(): Evidence = try {
        val dm = context.resources.displayMetrics
        val dpi = dm.densityDpi
        val w = dm.widthPixels
        val h = dm.heightPixels
        val vec = listOf("dpi=$dpi", "size=${w}x$h")

        when {
            dpi <= 0 || w <= 0 || h <= 0 ->
                ev("A.DISPLAY", Severity.MEDIUM, Verdict.HIT,
                    "显示参数异常", vec = vec)
            dpi < 120 ->
                ev("A.DISPLAY", Severity.LOW, Verdict.HIT,
                    "显示密度异常低", vec = vec,
                    detail = "真机 dpi 通常 320~560",
                    fp = "极低分辨率设备少见但存在")
            else -> ev("A.DISPLAY", Severity.INFO, Verdict.PASS,
                "显示参数正常", conf = Confidence.HIGH, vec = vec)
        }
    } catch (t: Throwable) {
        ev("A.DISPLAY", Severity.LOW, Verdict.UNKNOWN, "显示参数不可读")
    }

    // ------------------------------------------------------------- A.SIM_STATE
    private fun simState(): Evidence = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ev("A.SIM_STATE", Severity.LOW, Verdict.UNKNOWN, "TelephonyManager 不可用")
        val state = tm.simState
        val name = when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
            else -> "STATE_$state"
        }
        ev("A.SIM_STATE", Severity.INFO, Verdict.PASS, "SIM 状态已记录",
            conf = Confidence.HIGH, vec = listOf("state=$name"),
            detail = "云手机 / 模拟器通常是 ABSENT")
    } catch (t: Throwable) {
        ev("A.SIM_STATE", Severity.LOW, Verdict.UNKNOWN, "SIM 状态不可读")
    }

    // ---------------------------------------------------------------- helper
    private fun ev(
        id: String,
        sev: Severity,
        v: Verdict,
        title: String,
        cat: Category = Category.ARTIFACT,
        conf: Confidence = Confidence.MEDIUM,
        vec: List<String> = emptyList(),
        detail: String = "",
        fp: String = "",
    ) = Evidence(
        id = id, category = cat, severity = sev, confidence = conf,
        verdict = v, title = title, vectors = vec, detail = detail, fpNote = fp,
    )
}