package io.parkflow.probe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handoff = NavigationHandoff()
    private var permissionStart = false
    private var resumed = false
    private var stopped = false
    private var parkingToken: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: ParkingUi
    private lateinit var store: VisitStore
    private lateinit var content: LinearLayout
    private var ledger = VisitLedger()
    private var explicit: FlowRoute? = null
    private var current = FlowRoute(FlowPage.HOME)
    private var signature: Any? = null
    private var timer: TextView? = null
    private var diagnosticLog: TextView? = null
    private var draftId: String? = null
    private var draft = VisitNotes()
    private var draftCursors = intArrayOf(0, 0, 0, 0)
    private val fields = mutableListOf<EditText>()
    private var editorOptional = false
    private var editorReturn = FlowRoute(FlowPage.PARKED)
    private val refresh = object : Runnable { override fun run() { render(); handler.postDelayed(this, 1000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ParkingUi(this); store = VisitStore(this); ledger = store.load()
        ProbeState.activeVisit = ledger.active != null; ProbeState.gearContext()
        savedInstanceState?.let { b ->
            explicit = b.getString("page")?.let { FlowRoute(FlowPage.valueOf(it), b.getString("routeId"), b.getLong("selectionTime").takeIf { b.containsKey("selectionTime") }, b.getBoolean("gear")) }
            parkingToken = b.getString("parkingToken")
            draftId = b.getString("draftId")
            draft = VisitNotes(b.getString("floor", ""), b.getString("zone", ""), b.getString("pillar", ""), b.getString("note", ""))
            draftCursors = b.getIntArray("cursors") ?: draftCursors
            editorOptional = b.getBoolean("editorOptional", false)
            editorReturn = FlowRoute(FlowPage.valueOf(b.getString("returnPage", "PARKED")), b.getString("returnId"))
        }
        content = ui.column()
        setContentView(ScrollView(this).apply { id = R.id.page_scroll; setBackgroundColor(ui.canvas); isFillViewport = true; addView(content) })
        render()
        if (savedInstanceState == null) openNotificationIntent(intent)
    }
    private fun context() = FlowContext(ProbeState.running, ProbeState.snapshot.items.isNotEmpty(), ProbeState.selection, ledger, ProbeState.suggestedLotId, getSharedPreferences("pairing_intro", MODE_PRIVATE).getBoolean("seen", false))
    private fun navigate(route: FlowRoute?) { explicit = route; signature = null; render() }
    private fun command(action: String, configure: Intent.() -> Unit = {}) {
        if (!ProbeState.running) { toast("주차 준비를 먼저 시작해주세요"); return }
        startService(Intent(this, ProbeService::class.java).setAction(action).apply(configure))
    }
    private fun start() {
        // Remember preview dismissal independently from permission/SDK success.
        if (!getSharedPreferences("pairing_intro", MODE_PRIVATE).edit().putBoolean("seen", true).commit()) {
            toast("안내 확인을 저장하지 못했어요. 다시 시도해주세요."); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else startAfterPermission()
    }
    private fun startAfterPermission() {
        val reconnect = ProbeState.phase == ConnectionPhase.FAILED
        if (reconnect) stopService(Intent(this, ProbeService::class.java))
        // An old failed attempt must not consume the newly tapped request before START runs.
        if (!ProbeState.running || reconnect) ProbeState.phase = ConnectionPhase.CONNECTING
        handoff.begin(SystemClock.elapsedRealtime())
        getSharedPreferences("probe", MODE_PRIVATE).edit().putBoolean("auto", true).apply()
        val launch = {
            try { startForegroundService(Intent(this, ProbeService::class.java).setAction(ProbeService.START)); navigate(null) }
            catch (e: RuntimeException) {
                handoff.cancel(); ProbeState.phase = ConnectionPhase.FAILED
                ProbeState.flowError = "연결을 시작할 수 없어요. 다시 시도해주세요."
                toast(ProbeState.flowError!!); ProbeState.log(this, "Start failed: $e"); navigate(null)
            }
        }
        if (reconnect) { navigate(null); handler.postDelayed({ if (resumed && handoff.pending) launch() }, 350) }
        else launch()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && permissionStart) {
            permissionStart = false
            if (!stopped) {
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
                    toast("알림이 꺼져 있어요. 주차 기록은 앱에서 직접 열 수 있어요.")
                startAfterPermission()
            }
        }
    }
    private fun reloadLedger() {
        captureDraft(); ledger = store.load(); ProbeState.activeVisit = ledger.active != null; ProbeState.gearContext()
    }
    private fun openNotificationIntent(incoming: Intent?) {
        when (incoming?.action) {
            ProbeService.OPEN_COMPARISON -> openComparisonIntent(incoming)
            ProbeService.OPEN_PARKING -> openParkingIntent(incoming)
        }
    }
    private fun openComparisonIntent(incoming: Intent) {
        reloadLedger()
        val dirty = (current.page == FlowPage.EDIT_NOTES || explicit?.page == FlowPage.EDIT_NOTES) &&
            ledger.visits.firstOrNull { it.id == draftId }?.notes != draft
        if (dirty) { toast("작성 중인 메모를 먼저 저장해주세요."); return }
        if (store.error != null || !ProbeState.recommendationActions.consume(incoming.getStringExtra("token"),
                incoming.getLongExtra("generation", -1), incoming.getStringExtra("destination"),
                incoming.getIntExtra("minutes", -1), ProbeState.snapshot,
                getSharedPreferences("probe", MODE_PRIVATE).getInt("minutes", 60), ProbeState.running,
                ledger.active != null, ProbeState.selection != null, dirty, SystemClock.elapsedRealtime())) {
            toast("추천 알림이 만료되었거나 목적지가 바뀌었어요."); render(); return
        }
        // Activity and service callbacks share the main thread: cancel immediately after the
        // atomic token consume, never queue an untargeted CANCEL that could remove a newer card.
        getSystemService(android.app.NotificationManager::class.java).cancel(ProbeService.CARD_ID)
        handoff.cancel()
        navigate(FlowRoute(FlowPage.COMPARE))
        ProbeState.log(this, "Recommendation comparison opened directly; token consumed; no route request")
    }
    private fun openParkingIntent(incoming: Intent?) {
        if (incoming?.action != ProbeService.OPEN_PARKING) return
        reloadLedger()
        val token = incoming.getStringExtra("parkingToken")
        val lotId = incoming.getStringExtra("lotId")
        val requestedAt = incoming.getLongExtra("requestedAt", -1)
        // Keep a dirty editor intact; opening a notification must never discard typed input.
        if (current.page == FlowPage.EDIT_NOTES && selectedVisit()?.notes != draft) {
            toast("작성 중인 메모를 먼저 저장해주세요. 주차 확인은 앱에서 다시 열 수 있어요."); return
        }
        val ticket = ProbeState.parkingPrompt.open(token, lotId, requestedAt, SystemClock.elapsedRealtime())
        if (ticket == null || store.error != null) { toast("주차 확인 알림이 만료되었어요. 현재 선택을 확인해주세요."); render(); return }
        parkingToken = ticket.token
        navigate(FlowRoute(FlowPage.CONFIRM_PARKING, ticket.lotId, ticket.requestedAt, true))
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openNotificationIntent(intent) }
    private fun render() {
        if (resumed && handoff.expire(SystemClock.elapsedRealtime())) ProbeState.flowError = "내비 연결 시간이 지났어요. 내비로 시작하기를 다시 눌러주세요."
        if (resumed && handoff.consume(SystemClock.elapsedRealtime(), ProbeState.phase == ConnectionPhase.READY, ProbeState.phase == ConnectionPhase.FAILED)) command(ProbeService.FOREGROUND)
        ProbeState.gearContext()
        if (parkingToken != null && !ProbeState.parkingPrompt.canSave(parkingToken, explicit?.id, explicit?.selectionTime, SystemClock.elapsedRealtime())) {
            parkingToken = null; if (explicit?.page == FlowPage.CONFIRM_PARKING) explicit = null
        }
        val next = ParkingFlow.resolve(context(), explicit)
        if (explicit != null && next != explicit) explicit = null
        val key = if (next.page == FlowPage.EDIT_NOTES) listOf(next, store.error) else listOf(next, ProbeState.snapshot, ProbeState.selection, ProbeState.phase, ProbeState.destinationName, ProbeState.flowError, ProbeState.result, ProbeState.failure, ProbeState.destinationRead, ledger, store.error)
        if (signature != key) {
            captureDraft(); current = next; signature = key; timer = null; diagnosticLog = null; fields.clear(); content.removeAllViews()
            val sidePadding = if (next.page == FlowPage.PAIRING_INTRO && resources.configuration.screenWidthDp < 600) 12 else if (resources.configuration.screenWidthDp < 600) 20 else 40
            content.setPadding(ui.dp(sidePadding), ui.dp(28), ui.dp(sidePadding), ui.dp(40))
            header()
            store.error?.let { ui.text(content, it, 24f, ui.ink, true) }
            ProbeState.flowError?.let { ui.text(content, it, 24f, ui.ink) }
            if (ProbeState.failure != FlowFailure.NONE && ProbeState.phase == ConnectionPhase.READY && next.page in listOf(FlowPage.COMPARE, FlowPage.GUIDANCE, FlowPage.REQUESTING))
                ui.button(content, "목적지 다시 확인", false) { command(ProbeService.RETRY) }
            when (next.page) {
                FlowPage.PAIRING_INTRO -> pairingIntro()
                FlowPage.HOME -> home()
                FlowPage.WAITING -> waiting()
                FlowPage.COMPARE -> compare()
                FlowPage.REQUESTING, FlowPage.GUIDANCE -> guidance()
                FlowPage.CONFIRM_PARKING -> confirm()
                FlowPage.PARKED -> parked()
                FlowPage.EDIT_NOTES -> edit()
                FlowPage.EXIT_PREP -> exit()
                FlowPage.COMPLETED -> completed()
                FlowPage.HISTORY -> history()
                FlowPage.DETAIL -> detail()
                FlowPage.SETTINGS -> settings()
                FlowPage.SOURCES -> sources()
                FlowPage.DIAGNOSTICS -> diagnostics()
            }
        }
        val visit = ledger.visits.firstOrNull { it.id == current.id }
        timer?.text = visit?.let { "${it.elapsedMinutes(System.currentTimeMillis())}분 경과     ·     추정 ${fee(estimateFee(it.lot, it.elapsedMinutes(System.currentTimeMillis()).coerceAtLeast(1)))}\n확인 시각 기준 단순 추정 · 실제 청구액 아님" }
        diagnosticLog?.text = "SDK ${ProbeState.connection}\n${ProbeState.gearStatus}\n${ProbeState.destination}\n${ProbeState.logs(this)}"
    }
    private fun header() {
        val row = ui.row(); content.addView(row)
        val narrow = resources.configuration.screenWidthDp < 600
        val brand = TextView(this).apply { text = "ParkFlow"; textSize = if (narrow) 24f else 32f; setTextColor(ui.ink); ui.typography(this, true) }
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        listOf("기록" to FlowPage.HISTORY, "설정" to FlowPage.SETTINGS).forEach { (name, page) ->
            val button = ui.button(row, name, false) { leaveEditor { navigate(FlowRoute(page)) } }
            button.layoutParams = LinearLayout.LayoutParams(ui.dp(if (narrow) 80 else 112), -2).apply { leftMargin = ui.dp(8) }
            button.minHeight = ui.dp(64); button.textSize = 20f
            button.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        ui.divider(content)
    }
    private fun heading(eyebrow: String, title: String, subtitle: String? = null) {
        ui.text(content, eyebrow, 20f, ui.supporting, true); ui.text(content, title, 40f, bold = true)
        subtitle?.let { ui.text(content, it, 24f, ui.muted) }; ui.space(content, 18)
    }
    private fun dismissIntro() {
        if (!getSharedPreferences("pairing_intro", MODE_PRIVATE).edit().putBoolean("seen", true).commit()) {
            toast("안내 확인을 저장하지 못했어요. 다시 시도해주세요."); return
        }
        navigate(null)
    }
    private fun pairingIntro() {
        heading("휴대폰 연결 안내", "연결 화면 미리보기", "휴대폰 앱·계정 연결은 준비 중입니다. 현재 기록은 차량에만 저장돼요.")
        PairingIntro.render(ui, content) { start() }
    }
    private fun home() {
        heading("목적지부터 주차까지", "주차할 곳을 고르고,\n돌아올 위치를 기억하세요.", "내비 목적지 주변의 공공 주차자료를 비교하고\n주차 후 층과 기둥을 간편하게 남겨보세요.")
        val card = ui.card(content)
        ui.chip(card, "01 내비     →     02 주차 추천     →     03 기록")
        ui.text(card, "다음 목적지는 어디인가요?", 30f, bold = true)
        ui.text(card, "한 번 누르면 차량 내비를 열고 주차 추천을 준비합니다.", 24f, ui.muted)
        ui.button(card, "내비로 시작하기") { start() }
        ui.text(content, "정차 중 조작해주세요. 운영 여부와 빈자리는 현장에서 확인해주세요.", 20f, ui.muted)
    }
    private fun waiting() {
        val failed = ProbeState.phase == ConnectionPhase.FAILED
        val empty = ProbeState.result == ComparisonResult.EMPTY
        val unavailable = ProbeState.result == ComparisonResult.UNAVAILABLE
        val retry = ProbeState.failure != FlowFailure.NONE
        val reading = ProbeState.destinationRead == DestinationReadStatus.READING
        heading("01  /  목적지 확인", if (failed) "내비 연결을 다시 확인해주세요" else if (retry) "목적지를 다시 확인해주세요" else if (unavailable) "주차자료를 불러올 수 없어요" else if (reading) "목적지를 확인하고 있어요" else if (empty) "이 주변에는 비교할 자료가 없어요" else "내비에서 목적지를 선택해주세요", if (empty) "${ProbeState.destinationName} · 1km 내 공공 주차자료가 없습니다." else "목적지가 확인되면 가까운 주차장을 보여드릴게요.")
        val card = ui.card(content)
        ui.chip(card, when (ProbeState.phase) { ConnectionPhase.READY -> "차량 내비 연결됨"; ConnectionPhase.FAILED -> "연결 실패"; else -> "차량 내비 연결 중" })
        ui.text(card, if (retry) "내비 목적지를 다시 불러와\n주변 주차장을 확인할 수 있어요." else if (reading) "차량 내비에서 목적지를 확인하고 있어요.\n잠시 기다려주세요." else if (empty) "다른 목적지를 선택하면 다시 비교합니다." else "차량 내비에서 목적지를 정하면\n주차 추천 알림에서 바로 안내할 수 있어요.", 28f, bold = true)
        if (failed) ui.button(card, "다시 연결") { start() }
        else if (retry) ui.button(card, "목적지 다시 확인") { command(ProbeService.RETRY) }
        else ui.button(card, "내비로 시작하기") { start() }
        ui.text(content, "강남구 공공자료 50곳 기준 · 현재 운영·빈자리 미확인", 20f, ui.muted)
    }
    private fun compare() {
        val snapshot = ProbeState.snapshot
        heading("01  /  주차장 비교", "${ProbeState.destinationName ?: "목적지"} 주변 주차장", "가까운 순으로 비교해보세요. 요금은 선택한 시간의 단순 추정입니다.")
        val segments = ui.row().apply { background = ui.shape(ui.surface, 24, true); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }; content.addView(segments, LinearLayout.LayoutParams(-1, -2))
        stayChoices.forEach { minutes ->
            val button = ui.button(segments, when (minutes) { 30 -> "30분"; 60 -> "1시간"; 120 -> "2시간"; else -> "3시간" }, snapshot.minutes == minutes) {
                getSharedPreferences("probe", MODE_PRIVATE).edit().putInt("minutes", minutes).apply(); command(ProbeService.DURATION)
            }
            button.isSelected = snapshot.minutes == minutes
            button.contentDescription = "${button.text}, ${if (button.isSelected) "선택됨" else "선택 안 됨"}"
            button.textSize = if (resources.configuration.screenWidthDp < 600) 20f else 24f
            button.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
            button.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ui.space(content)
        val wide = resources.configuration.screenWidthDp >= 1100
        val container = if (wide) ui.row().apply { gravity = Gravity.TOP } else ui.column()
        content.addView(container)
        snapshot.items.take(3).forEachIndexed { index, candidate ->
            val card = ui.card(container)
            if (wide) card.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (index < snapshot.items.lastIndex) ui.dp(24) else 0 }
            ui.chip(card, if (index == 0) "가까운 곳" else "주변 주차장 ${index + 1}")
            ui.text(card, candidate.lot.displayName, 30f, bold = true).minHeight = ui.dp(76)
            ui.text(card, candidate.lot.address, 22f, ui.muted).minHeight = ui.dp(90)
            ui.text(card, fee(candidate.estimatedWon), 48f, bold = true)
            ui.text(card, "${snapshot.minutes}분 추정 · 할인 미반영", 20f, ui.muted)
            ui.text(card, "직선 ${candidate.distanceMeters.toInt()}m", 24f, ui.supporting, true)
            ui.text(card, "공공자료 ${candidate.lot.sourceDate.take(7).replace('-', '.')}\n운영·빈자리·현재 요금 미확인", 20f, ui.muted)
            ui.button(card, "이곳으로 안내") { command(ProbeService.SELECT) { putExtra("lotId", candidate.lot.id); putExtra("generation", snapshot.generation); putExtra("foregroundTappedAt", SystemClock.elapsedRealtime()) } }
        }
        ui.text(content, "직선거리 기준 · 주차장 입구 미검증 · 일주차권 미반영", 20f, ui.muted)
        ui.button(content, "자료 안내", false) { navigate(FlowRoute(FlowPage.SOURCES)) }
    }
    private fun guidance() {
        val selection = ProbeState.selection ?: return
        val accepted = selection.state == NavigationSelectionState.ACCEPTED
        heading("02  /  주차장 안내", if (accepted) "내비 안내를 확인해주세요" else "내비에 안내를 요청했어요", if (accepted) "선택한 주차장이 내비 목적지로 확인됐어요." else "내비에서 목적지를 확인하고 있어요. 잠시 기다려주세요.")
        val card = ui.card(content); ui.chip(card, if (accepted) "안내 연결됨" else "안내 요청 중")
        ui.text(card, selection.lot.displayName, 32f, bold = true); ui.text(card, selection.lot.address, 24f, ui.muted)
        ui.button(card, "내비로 돌아가기") { command(ProbeService.FOREGROUND) }
        if (accepted) ui.button(card, "주차했어요", false) { parkingToken = null; navigate(FlowRoute(FlowPage.CONFIRM_PARKING, selection.lot.id, selection.requestedAt)) }
        ui.text(content, "P 전환 알림을 사용할 수 없으면 주차했어요를 눌러 기록하세요.\n도착이나 실제 주차를 자동으로 판단하지 않습니다.", 24f, ui.muted)
    }
    private fun confirm() {
        val selected = ProbeState.selection ?: return
        heading("03  /  주차 확인", "이곳에 주차하셨나요?", if (current.gear) "기어가 P로 바뀌었어요. 주차 여부는 직접 확인해주세요." else "주차를 마친 뒤 기록을 저장해주세요.")
        val card = ui.card(content); ui.text(card, selected.lot.displayName, 32f, bold = true); ui.text(card, selected.lot.address, 24f, ui.muted)
        ui.text(card, "선택한 주차장 위치를 기준으로 저장합니다.\n차량 측정 위치나 실제 입차 시각이 아닙니다.", 26f, ui.muted)
        val route = current
        val token = parkingToken
        ui.button(card, "주차 확인하고 기록하기") {
            reloadLedger()
            val latest = ParkingFlow.resolve(context(), route)
            if (store.error != null || latest != route || ProbeState.selection?.state != NavigationSelectionState.ACCEPTED ||
                (token != null && !ProbeState.parkingPrompt.canSave(token, route.id, route.selectionTime, SystemClock.elapsedRealtime()))) { toast("선택 또는 기어 상태가 바뀌었어요"); navigate(null) }
            else if (persist(ledger.confirm(selected.lot, if (route.gear) EntryMode.GEAR_P_CONFIRMED else EntryMode.MANUAL, System.currentTimeMillis()))) {
                // Commit the record first. A failed save must leave the live listener intact.
                val visit = ledger.active ?: return@button
                parkingToken = null; handoff.cancel()
                stopService(Intent(this, ProbeService::class.java))
                ProbeState.selection = null; ProbeState.resetGear()
                openEditor(visit, FlowRoute(FlowPage.PARKED, visit.id), optional = true)
            }
        }.isEnabled = store.error == null
        ui.button(card, "아직 주차 전이에요", false) { ProbeState.dismissSuggestion(); navigate(null) }
    }
    private fun selectedVisit() = ledger.visits.firstOrNull { it.id == current.id }
    private fun visitCard(visit: ParkingVisit, elapsed: Boolean = true) {
        val card = ui.card(content); ui.chip(card, if (visit.status == VisitStatus.ACTIVE) "주차 기록 중" else "종료한 기록")
        ui.text(card, visit.lot.displayName, 32f, bold = true); ui.text(card, "주차 확인  ${date(visit.confirmedAt)}", 24f, ui.muted)
        val row = ui.row(); card.addView(row)
        listOf("층" to visit.notes.floor, "구역" to visit.notes.zone, "기둥" to visit.notes.pillar).forEach { (title, value) ->
            val col = ui.column(); row.addView(col, LinearLayout.LayoutParams(0, -2, 1f)); ui.text(col, title, 20f, ui.muted); ui.text(col, value.ifBlank { "—" }, 48f, bold = true)
        }
        if (visit.notes.note.isNotBlank()) ui.text(card, visit.notes.note, 24f)
        if (elapsed) timer = ui.text(card, "", 24f, ui.muted)
    }
    private fun parked() {
        val visit = selectedVisit() ?: return
        heading("내 차의 위치", "돌아올 때도, 편안하게.")
        visitCard(visit)
        ui.button(content, if (visit.notes == VisitNotes()) "위치 메모 남기기" else "위치 메모 수정") { openEditor(visit) }
        ui.button(content, "출차 준비", false) { navigate(FlowRoute(FlowPage.EXIT_PREP, visit.id)) }
        ui.text(content, "선택한 주차장 기준 위치 · 직접 확인해 저장한 기록", 20f, ui.muted)
    }
    private fun openEditor(visit: ParkingVisit, returnTo: FlowRoute = current, optional: Boolean = false) {
        editorOptional = optional; editorReturn = returnTo; draftId = visit.id; draft = visit.notes; draftCursors = intArrayOf(0, 0, 0, 0)
        navigate(FlowRoute(FlowPage.EDIT_NOTES, visit.id))
    }
    private fun captureDraft() {
        if (current.page != FlowPage.EDIT_NOTES || fields.size != 4) return
        draft = VisitNotes(fields[0].text.toString(), fields[1].text.toString(), fields[2].text.toString(), fields[3].text.toString())
        draftCursors = fields.map { it.selectionStart.coerceAtLeast(0) }.toIntArray()
    }
    private fun edit() {
        val visit = selectedVisit() ?: return
        if (draftId != visit.id) { draftId = visit.id; draft = visit.notes }
        heading("위치 메모", "어디에 주차하셨나요?", if (editorOptional) "주차 기록을 저장했어요. 위치 메모는 나중에 남겨도 돼요.\n${visit.lot.displayName}" else visit.lot.displayName)
        val card = ui.card(content)
        val wide = resources.configuration.screenWidthDp >= 850
        val locationFields = if (wide) ui.row().apply { gravity = Gravity.TOP; card.addView(this, LinearLayout.LayoutParams(-1, -2)) } else card
        listOf("층" to draft.floor, "구역" to draft.zone, "기둥" to draft.pillar, "메모 (선택)" to draft.note).forEachIndexed { index, (title, value) ->
            val group = ui.column()
            val parent = if (index < 3) locationFields else card
            parent.addView(group, if (wide && index < 3) LinearLayout.LayoutParams(0, -2, 1f).apply { if (index < 2) rightMargin = ui.dp(20) } else LinearLayout.LayoutParams(-1, -2))
            val label = ui.text(group, title, 24f, ui.supporting, true)
            label.labelFor = 2000 + index
            val field = EditText(this).apply {
                id = 2000 + index; contentDescription = title; textSize = 26f
                setSingleLine(index != 3); ui.field(this)
                setText(value); setSelection(draftCursors[index].coerceAtMost(value.length))
            }
            group.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(22) }); fields.add(field)
        }
        ui.button(content, "메모 저장") { captureDraft(); if (persist(ledger.notes(visit.id, draft))) { fields.clear(); navigate(editorReturn) } }
        ui.button(content, if (editorOptional) "나중에 입력" else "돌아가기", false) { leaveEditor { navigate(editorReturn) } }
    }
    private fun leaveEditor(action: () -> Unit) {
        captureDraft()
        if (current.page == FlowPage.EDIT_NOTES && selectedVisit()?.notes != draft)
            AlertDialog.Builder(this).setTitle("작성 중인 메모가 있어요").setMessage("저장하지 않고 나갈까요?")
                .setNegativeButton("계속 작성", null).setPositiveButton("저장하지 않고 나가기") { _, _ -> fields.clear(); action() }.show()
        else action()
    }
    private fun exit() {
        val visit = selectedVisit() ?: return
        heading("출차 준비", "주차 기록을 확인해주세요", "정산은 시설 안내를 확인해주세요.")
        visitCard(visit)
        ui.text(content, "온라인 정산 미연동\n기록을 마쳐도 결제 완료나 실제 출차를 뜻하지 않습니다.", 26f, ui.muted)
        ui.button(content, "주차 기록 마치기") { if (persist(ledger.close(visit.id, System.currentTimeMillis()))) navigate(FlowRoute(FlowPage.COMPLETED, visit.id)) }
        ui.button(content, "돌아가기", false) { navigate(null) }
    }
    private fun completed() {
        val visit = selectedVisit() ?: return
        heading("기록 완료", "주차 기록을 마쳤어요", "저장한 위치와 메모는 기록에서 다시 볼 수 있어요.")
        visitCard(visit); ui.text(content, "기록 종료  ${date(visit.endedAt ?: visit.confirmedAt)}", 26f, ui.muted)
        ui.button(content, "처음으로") { returnHome() }
    }
    private fun returnHome() {
        stopService(Intent(this, ProbeService::class.java)); ProbeState.selection = null; ProbeState.resetGear()
        navigate(null); handler.postDelayed({ navigate(null) }, 300)
    }
    private fun history() {
        heading("주차 기록", "지난 위치를 다시 찾아보세요")
        if (ledger.visits.isEmpty()) ui.text(ui.card(content), "아직 저장한 기록이 없어요.", 26f)
        ledger.visits.forEach { visit ->
            val card = ui.card(content); ui.text(card, visit.lot.displayName, 32f, bold = true)
            ui.text(card, "${date(visit.confirmedAt)}  ·  ${if (visit.status == VisitStatus.ACTIVE) "기록 중" else "종료"}", 24f, ui.muted)
            ui.button(card, "기록 보기", false) { navigate(FlowRoute(FlowPage.DETAIL, visit.id)) }
        }
        ui.button(content, "돌아가기", false) { navigate(null) }
    }
    private fun detail() {
        val visit = selectedVisit() ?: return
        heading("기록 상세", "저장한 주차 위치")
        visitCard(visit); ui.text(content, visit.lot.address, 26f, ui.muted)
        visit.endedAt?.let { ui.text(content, "기록 종료 ${date(it)}", 24f, ui.muted) }
        ui.button(content, "위치 메모 수정") { openEditor(visit) }
        ui.button(content, "기록 목록", false) { navigate(FlowRoute(FlowPage.HISTORY)) }
    }
    private fun settings() {
        heading("설정", "필요한 연결과 안내")
        val card = ui.card(content); ui.text(card, "내비 연결", 32f, bold = true)
        ui.text(card, if (ProbeState.running) "주차 준비가 실행 중입니다." else "연결이 중지되어 있습니다.", 26f, ui.muted)
        if (ProbeState.running) ui.button(card, "주차 준비 중지", false) { stopService(Intent(this, ProbeService::class.java)); navigate(null) }
        ui.button(card, "알림 설정", false) { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        ui.button(content, "휴대폰 연결 안내", false) { navigate(FlowRoute(FlowPage.PAIRING_INTRO)) }
        ui.button(content, "자료 안내", false) { navigate(FlowRoute(FlowPage.SOURCES)) }
        ui.button(content, "개발 진단", false) { navigate(FlowRoute(FlowPage.DIAGNOSTICS)) }
        ui.button(content, "돌아가기", false) { navigate(null) }
    }
    private fun sources() {
        heading("자료 안내", "비교하기 전에 알아두세요")
        val card = ui.card(content)
        ui.text(card, "서울특별시 강남구 공영주차장 정보", 32f, bold = true)
        ui.text(card, "공공데이터포털 · 강남구 · 공공누리 제1유형\n자료일 2025-09-05 · 로컬 자료 50건\nhttps://www.data.go.kr/data/15115393/fileData.do", 26f, ui.muted).setTextIsSelectable(true)
        ui.text(card, "직선거리이며 주차장 입구는 검증하지 않았습니다.\n할인·일주차권 미반영 단순 요금 추정입니다.\n현재 요금·운영·빈자리는 현장에서 확인해주세요.\n주차 시각과 위치는 사용자가 확인한 기준입니다.", 24f)
        ui.text(card, "글꼴 · Asta Sans", 32f, bold = true)
        ui.text(card, "Copyright 2024 The Asta Sans Project Authors.\nSIL Open Font License 1.1 · 원본 글꼴을 포함합니다.", 24f, ui.muted)
        ui.button(card, "Asta Sans 라이선스 보기", false) {
            val license = assets.open("licenses/AstaSans-OFL.txt").bufferedReader().use { it.readText() }
            val text = TextView(this).apply { this.text = license; textSize = 20f; setTextColor(ui.ink); ui.typography(this); setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24)); setTextIsSelectable(true) }
            AlertDialog.Builder(this).setTitle("Asta Sans · SIL OFL 1.1")
                .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("닫기", null).show()
        }
        ui.button(content, "돌아가기", false) { navigate(null) }
    }
    private fun diagnostics() {
        heading("개발 진단", "테스트 도구 · 실제 주차정보 아님", "가상 요금과 예시 미리보기는 진단 목적으로만 사용합니다.")
        val card = ui.card(content)
        ui.button(card, "현재 내비 목적지 수동 조회", false) { command(ProbeService.READ) }
        ui.button(card, "삼성역 예시 미리보기 · 안내하지 않음", false) {
            val lots = try { loadParkingLots(this) } catch (_: Exception) { toast("자료 사용 불가"); return@button }
            AlertDialog.Builder(this).setTitle("예시 미리보기 · 실제 내비 이벤트 아님").setMessage(RecommendationEngine(lots).nearby(37.508844, 127.06316).joinToString("\n\n") { "${it.lot.displayName} · 직선 ${it.distanceMeters.toInt()}m · 추정 ${fee(it.estimatedWon)}" }).setPositiveButton("닫기", null).show()
        }
        ui.button(card, "수동 DEMO 알림 · 가상 요금", false) { command(ProbeService.POST) }
        ui.button(card, "수동 DEMO 10초 후 알림", false) { command(ProbeService.DELAY) }
        ui.button(card, "추천 알림 취소", false) { command(ProbeService.CANCEL) }
        diagnosticLog = ui.text(content, "", 20f, ui.muted).apply { setTextIsSelectable(true) }
        ui.button(content, "설정으로", false) { navigate(FlowRoute(FlowPage.SETTINGS)) }
    }
    private fun persist(next: VisitLedger): Boolean {
        if (!store.save(next)) { toast(store.error ?: "기록 저장 실패"); signature = null; render(); return false }
        ledger = next; ProbeState.activeVisit = ledger.active != null; ProbeState.gearContext(); return true
    }
    private fun fee(value: Long?) = value?.let { "${NumberFormat.getNumberInstance(Locale.KOREA).format(it)}원" } ?: "요금 미상"
    private fun date(value: Long) = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(value))
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    @Deprecated("Uses native back handling")
    override fun onBackPressed() {
        when (current.page) {
            FlowPage.PAIRING_INTRO -> dismissIntro()
            FlowPage.HOME -> super.onBackPressed()
            FlowPage.COMPLETED -> returnHome()
            FlowPage.CONFIRM_PARKING -> { ProbeState.dismissSuggestion(); navigate(null) }
            FlowPage.EDIT_NOTES -> leaveEditor { navigate(editorReturn) }
            FlowPage.DETAIL -> navigate(FlowRoute(FlowPage.HISTORY))
            FlowPage.SOURCES, FlowPage.DIAGNOSTICS -> navigate(FlowRoute(FlowPage.SETTINGS))
            else -> navigate(null)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("parkingToken", parkingToken)
        captureDraft(); explicit?.let { outState.putString("page", it.page.name); outState.putString("routeId", it.id); it.selectionTime?.let { time -> outState.putLong("selectionTime", time) }; outState.putBoolean("gear", it.gear) }
        outState.putString("draftId", draftId); outState.putString("floor", draft.floor); outState.putString("zone", draft.zone); outState.putString("pillar", draft.pillar); outState.putString("note", draft.note); outState.putIntArray("cursors", draftCursors)
        outState.putBoolean("editorOptional", editorOptional)
        outState.putString("returnPage", editorReturn.page.name); outState.putString("returnId", editorReturn.id)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); resumed = true; stopped = false; reloadLedger(); handler.post(refresh) }
    override fun onPause() { resumed = false; handoff.cancel(); handler.removeCallbacks(refresh); super.onPause() }
    override fun onStop() { stopped = true; permissionStart = false; handoff.cancel(); super.onStop() }
}
