package io.parkflow.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.ceil

/** A static concept preview, with no pairing action or linked-account state. */
object PairingIntro {
    fun render(ui: ParkingUi, parent: LinearLayout, continueInVehicle: () -> Unit) {
        val wide = ui.context.resources.configuration.screenWidthDp >= 850
        val card = ui.card(parent)
        card.setPadding(ui.dp(8), ui.dp(20), ui.dp(8), ui.dp(20))
        val layout = if (wide) ui.row() else ui.column()
        layout.gravity = Gravity.CENTER
        card.addView(layout, LinearLayout.LayoutParams(-1, -2))
        val qrColumn = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        layout.addView(qrColumn, LinearLayout.LayoutParams(-2, -2).apply {
            if (wide) rightMargin = ui.dp(32)
        })
        // The PNG is one pixel per module, including four white modules on every side.
        // Scale by whole pixels only: no interpolation, resource density scaling or cropping.
        val source = BitmapFactory.decodeResource(ui.context.resources, R.drawable.pairing_demo_qr,
            BitmapFactory.Options().apply { inScaled = false })
        val scale = ceil(ui.dp(280).toDouble() / source.width).toInt().coerceAtLeast(1)
        val size = source.width * scale
        val bitmap = Bitmap.createScaledBitmap(source, size, size, false)
        qrColumn.addView(ImageView(ui.context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundColor(0xffffffff.toInt())
            contentDescription = "휴대폰 연결 화면 미리보기 QR 코드. 데모 주소이며 실제 계정 연결은 지원하지 않습니다."
        }, LinearLayout.LayoutParams(size, size))
        ui.text(qrColumn, "미리보기 전용 QR", 20f, ui.supporting, true).gravity = Gravity.CENTER
        val steps = ui.column()
        layout.addView(steps, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(20); leftMargin = ui.dp(16); rightMargin = ui.dp(16)
        })
        ui.chip(steps, "앞으로의 연결 흐름 · 준비 중")
        ui.text(steps, "01  휴대폰으로 QR 확인", 26f, bold = true)
        ui.text(steps, "02  휴대폰 앱에서 계정 연결", 26f, bold = true)
        ui.text(steps, "03  차량과 주차 기록 이어보기", 26f, bold = true)
        ui.text(steps, "현재는 연결 동작을 제공하지 않습니다.\nQR을 스캔해도 앱·계정이 연결되거나 기록이 전송되지 않아요.", 24f, ui.muted)
        ui.button(if (wide) steps else parent, "내비로 시작하기", action = continueInVehicle)
        ui.text(parent, "설정 → 휴대폰 연결 안내에서 다시 볼 수 있어요.", 20f, ui.muted)
    }
}
