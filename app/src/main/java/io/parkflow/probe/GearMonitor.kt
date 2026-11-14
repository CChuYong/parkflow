package io.parkflow.probe

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Public AAOS powertrain observation only. No writes, location or arrival inference. */
class GearMonitor(private val context: Context, private val changed: (Boolean?) -> Unit) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var started = false
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>?) {
            if (!started) return
            if (value == null) { unavailable("기어 신호 없음"); return }
            if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
            ProbeState.log(context, "AAOS GEAR_SELECTION callback value=${value.value} status=${value.status} timestampNanos=${value.timestamp}")
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) { unavailable("기어 신호 사용 불가"); return }
            val gear = value.value as? Int
            val known = gear != null && gear in setOf(VehicleGear.GEAR_PARK, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL,
                VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
                VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH, VehicleGear.GEAR_SEVENTH,
                VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH)
            if (!known) { unavailable("기어 값 미확인"); return }
            ProbeState.gearStatus = "실시간 기어: ${VehicleGear.toString(gear!!)} · P 전환도 주차 확인은 필요합니다"
            changed(gear == VehicleGear.GEAR_PARK)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (started && propertyId == VehiclePropertyIds.GEAR_SELECTION) unavailable("기어 신호 오류")
        }
    }
    fun start() {
        if (started) return
        started = true
        if (context.checkSelfPermission("android.car.permission.CAR_POWERTRAIN") != PackageManager.PERMISSION_GRANTED) {
            unavailable("기어 읽기 권한 없음"); return
        }
        ProbeState.gearStatus = "차량 기어 서비스 연결 중 · 수동 기록 가능"
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!started) { connectedCar.disconnect(); return@createCar }
                if (!ready) { manager = null; unavailable("차량 기어 서비스 연결 끊김"); return@createCar }
                try {
                    val properties = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    manager = properties
                    if (properties?.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null) unavailable("차량에서 기어 속성을 지원하지 않음")
                    else if (!properties.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                        unavailable("기어 신호 구독 불가")
                    else ProbeState.log(context, "AAOS GEAR_SELECTION public callback registered; initial callback is baseline")
                } catch (e: Exception) { unavailable("기어 읽기 불가"); ProbeState.log(context, "Gear subscribe failed: $e") }
            }
            if (car == null) unavailable("차량 기어 서비스 없음")
        } catch (e: Exception) { unavailable("차량 기어 연결 불가"); ProbeState.log(context, "Gear connect failed: $e") }
    }
    private fun unavailable(reason: String) {
        ProbeState.gearStatus = "$reason · 수동 기록 가능"
        ProbeState.log(context, "AAOS gear unavailable: $reason; baseline/suggestion reset")
        changed(null)
    }
    fun stop() {
        started = false
        try { manager?.unregisterCallback(callback) } catch (_: Exception) { }
        manager = null
        try { car?.disconnect() } catch (_: Exception) { }
        car = null; unavailable("기어 모니터 중지")
    }
}
