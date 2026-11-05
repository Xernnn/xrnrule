# Domain Contract — xrnrule (Android, formerly Habit First)

Trạng thái: **v1 draft**, chốt ở Phase 0. Đây là nguồn sự thật cho hành vi máy trạng thái.
Mọi thay đổi hành vi phải sửa file này *trước*, rồi mới sửa code.

Module hiện thực: `:domain` (Kotlin thuần, JVM, **không** phụ thuộc Android). Tất cả bất biến
dưới đây phải có unit test tương ứng trong `:domain/src/test`.

---

## 1. Từ vựng

| Thuật ngữ | Nghĩa |
|---|---|
| **Block** | Một luật: "các app đích này bị chặn cho tới khi điều kiện habit đạt". `targetPackages` là một **Set** — một Block có thể phủ nhiều app cùng lúc (block nhiều app bằng một luật); mọi test thành viên (shield, accrual) đều là OR trên tập này. |
| **Target** | (Các) app bị chặn — nhận diện qua `packageName` hoặc `category` — và/hoặc (các) website trong `targetSites`. |
| **Website target** | Một tên miền trong `block.targetSites`, đã chuẩn hóa (chữ thường, bỏ scheme/path/port, bỏ `www.`). Host `h` khớp target `t` ⟺ `h == t` hoặc `h` kết thúc bằng `"." + t` (chặn `youtube.com` thì chặn luôn `m.youtube.com`). Host đang mở được lớp Android đọc từ thanh địa chỉ của trình duyệt foreground. |
| **BlockMode** | Cách một Block quyết định có đang chặn hay không: `HABIT` (máy trạng thái §2 — mặc định), `ALLOWANCE` (hạn mức dùng mỗi ngày), `SCHEDULE` (chặn theo lịch), `LOCKDOWN` (chặn một khoảng thời gian tính từ lúc bắt đầu). Xem §2.2. |
| **Lockdown** | Một lần chặn `LOCKDOWN` đang chạy: `{startedAtMonotonic, startedAtWall, durationMs}`, lưu ở `World.lockdowns`. |
| **Trigger** | Điều kiện để ARMED → EARNED. Một trong: `TIMER`, `CHECKLIST`, `COMBINED` (TIMER + CHECKLIST cùng lúc). (`COUNT` cũ đã bỏ: không có gì đếm được nó; habit đếm được giờ là habit item trong CHECKLIST, I36.) |
| **CombineMode** | Chỉ dùng khi `trigger.type == COMBINED`: `AND` (cần cả hai) hoặc `OR` (cần một trong hai). |
| **Habit app** | `trigger.habitPackages` — app(s) nơi đồng hồ TIMER đếm giờ. Rỗng (mặc định) nghĩa là "đếm giờ trong chính app Target"; nếu khác rỗng, đếm giờ trong (các) app này thay vì Target — cho phép habit "mở app khác N phút để unlock app bị chặn". |
| **ChecklistItem** | Một mục to-do thuộc một Block (chỉ dùng khi `trigger.type` là `CHECKLIST` hoặc `COMBINED`). |
| **Habit** | Một habit có tên trong **danh sách habit duy nhất** (`World.habits`), dùng chung giữa các thiết bị qua **habit server** (§2.5), không qua `SharedState`; nhiều block cùng trỏ tới một habit được. Mô hình là của Loop Habit Tracker (xrnloops, bản fork Loop, vẫn là app theo dõi habit riêng) cộng thêm đồng hồ và icon: `kind` `CHECK` (Loop: yes/no), `COUNT` (Loop: số, có `unit`), `TIMER` (Loop: số, đơn vị phút, đếm bằng đồng hồ tập trung, I37); `goal` (số, xem *Lượng*), `targetType` `AT_LEAST`/`AT_MOST` (habit số), `freqNum`/`freqDen` (`CHECK`: `freqNum` lần trong `freqDen` ngày; habit số: `goal` cho mỗi `freqDen` ngày), `color` (chỉ số bảng màu Loop 0–19), `icon`, `question`, `description`, `archived`, `position`, `reminder`. |
| **Lượng** | Theo cách mã hóa của Loop: số nguyên **phần nghìn** đơn vị của habit (20 cái = 20 000). Đơn vị của `TIMER` là phút: 1 phút = 1000, nên `ms` của đồng hồ = `ms / 60`. |
| **Ngày habit** | Ngày mà habit được ghi: ngày local của `now_wall − habitDayStartHour giờ` (`World.habitDayStartHour`, 0–23, lấy từ habit server: Loop "kéo dài ngày qua nửa đêm" = 3). Tick lúc 1 giờ sáng khi giờ bắt đầu là 3 thuộc về hôm trước. Chỉ habit dùng ngày này; block, hạn mức, lịch vẫn theo nửa đêm. |
| **Habit entry** | `World.habitEntries["habitId@epochDay"]` (ngày habit) = `HabitEntry(value, notes, mtime, seq, dirty, baseValue)`, giữ **toàn bộ** lịch sử. `value` theo Loop: habit `CHECK` có `UNKNOWN=-1`, `NO=0`, `YES_AUTO=1` (Loop tự điền, không bao giờ lưu), `YES_MANUAL=2`, `SKIP=3`; habit số là lượng. `seq`, `dirty`, `baseValue`: sổ sách đồng bộ (I38). |
| **Habit item** | `ChecklistItem` có `habitId != null`: block cần habit đó. `linkedDay` chọn ngày habit nào được tính: `TODAY` (mặc định) hoặc `YESTERDAY` (habit thường tick cuối ngày). `countsGoal` (habit `CHECK` không hằng ngày, kiểu "3 lần/tuần"): tính cả ngày Loop tự điền vì đã đạt mục tiêu. Xong khi habit có đủ **một phần mở khóa** trong ngày đó mà block này chưa tiêu (I23, I24, I36). User không tick tay được. *Linked item* cũ (`externalHabitId`, khi habit còn nằm ở xrnloops) được đổi thành habit item cùng id (I23). |
| **Tag item** | `ChecklistItem` có `tag != null`: block cần điện thoại đọc một thẻ NFC (`Tags.id`: 8 byte đầu SHA-256 của UID, không lưu UID). Chạm tay không tick được, đọc đúng thẻ thì tick (I43). Như mục thường, `done` của nó được chia sẻ, nên quét bằng điện thoại cũng mở được block ở laptop. |
| **Focus run** | `World.focus`: đồng hồ tập trung đang chạy (hoặc đang dừng) cho một habit `TIMER`. Chỉ ở máy này (I37). |
| **Credit** | Quyền mở khóa đã kiếm được, có hạn dùng (TTL). |
| **Pause** | Giờ nghỉ ngắn (mặc định 15 phút, tối đa một lần mỗi ngày local) gỡ chặn mọi block `SOFT` không phải `LOCKDOWN` (I25). |
| **Unlock session** | Khoảng thời gian shield tắt sau khi redeem credit. |
| **Shield** | Cơ chế chặn thực tế (overlay). "Shield ON" = target bị chặn. |
| **Monotonic clock** | `SystemClock.elapsedRealtime()` — không nhảy khi user chỉnh giờ. |
| **Wall clock** | `System.currentTimeMillis()` — giờ hệ thống, user chỉnh được. |
| **Desktop app target** | `block.targetDesktopApps`: id app trên laptop (tên file `.desktop` mà KWin báo, vd `steam`). Laptop coi chúng như `targetPackages` (I29); điện thoại không bao giờ thấy chúng ở foreground. |
| **Shared state** | Phần `World` mà các thiết bị (điện thoại, laptop) dùng chung qua server đồng bộ: `SharedState`, xem §2.4. |

---

## 2. Máy trạng thái

Ba trạng thái: `ARMED`, `EARNED`, `UNLOCKED`. Một Block luôn ở đúng một trạng thái.

```
ARMED    ──[triggerMet]──────────────▶ EARNED
EARNED   ──[creditExpired|dayRollover|userReset|habitUndone]──▶ ARMED
EARNED   ──[redeem]────────────────────▶ UNLOCKED
UNLOCKED ──[timeUp|tamper]─────────────▶ ARMED
```

Không có transition nào khác. Cụ thể: **không** có UNLOCKED → EARNED, **không** có
EARNED → EARNED, **không** có ARMED → UNLOCKED. (Ngoại lệ duy nhất là *nhận* trạng thái từ
thiết bị khác, §2.4: máy này thấy block mở vì máy kia đã redeem — I27.)

### 2.1 Điều kiện transition

| Transition | Guard | Side effect (theo thứ tự) |
|---|---|---|
| `ARMED → EARNED` | `triggerMet(progress, trigger)` == true | 1. persist state=EARNED + tạo `EarnedCredit`  2. shield vẫn ON (no-op) |
| `EARNED → ARMED` | `now_monotonic ≥ credit.expiresAtMonotonic` **hoặc** ngày local đổi so với `progress.windowStart` **hoặc** user bấm "hủy" **hoặc** `habitUndone` (I36: habit item đã xong ở máy này không còn đủ lượng và trigger không còn đạt) | 1. persist state=ARMED  2. **reset `HabitProgress` về 0**  3. **reset mọi `ChecklistItem.done = false`**  4. xóa `EarnedCredit`  5. shield vẫn ON |
| `EARNED → UNLOCKED` | user bấm "Redeem" **và** `committed + credit.amountMs ≤ dailyCapMs` (I9: đã dùng hôm nay + session đang mở) | 1. persist state=UNLOCKED + `UnlockSession{startedAtMonotonic=now_mono, startedAtWall=now_wall, durationMs=credit.amountMs}` + tiêu một phần mở khóa của mọi habit item đủ lượng (I24)  2. xóa `EarnedCredit`  3. **gỡ shield** (OFF) |
| `UNLOCKED → ARMED` | `elapsedMonotonic ≥ session.durationMs` (timeUp) **hoặc** `tamperDetected` (I3); session `selfPaced` thì thay bằng: thời gian **dùng** `Σ session.usedMs ≥ durationMs` **hoặc** đã sang ngày mới (I41) | 1. persist state=ARMED  2. cộng thời gian đã dùng vào `dailyUnlockUsedMs`  3. reset `HabitProgress` + `ChecklistItem.done` (như EARNED→ARMED)  4. **áp lại shield** (ON) |

`triggerMet`:

```
fun triggerMet(progress, trigger, checklist): Boolean = when (trigger.type) {
    TIMER     -> progress.accumulatedMs >= trigger.requiredAmount
    CHECKLIST -> {
        val need = if (trigger.requiredAmount == -1L) checklist.size.toLong()
                   else trigger.requiredAmount
        checklist.count { it.done } >= need && checklist.isNotEmpty()
    }
    COMBINED  -> {
        val timerMet     = progress.accumulatedMs >= trigger.requiredAmount
        val checklistMet = checklist.isNotEmpty() && checklist.all { it.done } // luôn "hết sạch", không có ngưỡng riêng
        when (trigger.combineMode) {
            AND -> timerMet && checklistMet
            OR  -> timerMet || checklistMet
        }
    }
}
```

`COMBINED` dùng chung `requiredAmount`/`unit` của trigger làm ngưỡng cho **nửa TIMER**; nửa
CHECKLIST của nó luôn nghĩa là "mọi mục đã tick" (không có ngưỡng từng phần riêng — UI hiện tại
cũng chưa bao giờ tạo CHECKLIST với ngưỡng từng phần, nên đây không phải một giới hạn thực tế).

Đồng hồ TIMER (cho cả `TIMER` lẫn `COMBINED`) đếm giờ theo `trigger.habitPackages` nếu khác
rỗng, nếu không thì đếm theo target của block (`targetPackages` + `targetSites`) — xem I16, I18.

### 2.2 Chế độ chặn (`BlockMode`)

Máy trạng thái ở trên chỉ áp dụng cho `mode == HABIT`. Ba chế độ còn lại **không có đường
mở khóa**: block luôn ở `ARMED`, trigger/checklist/credit bị bỏ qua, và engine không bao giờ
chuyển trạng thái cho chúng. "Đang chặn" của từng chế độ, khi block chỉ có một luật (nhiều luật:
xem I44 ngay dưới bảng):

| Mode | Đang chặn khi | Tham số |
|---|---|---|
| `HABIT` | `enabled && state != UNLOCKED && scheduleActive` (như cũ, I5) | trigger, reward, schedule |
| `ALLOWANCE` | `enabled && scheduleActive && (đang nghỉ bắt buộc (I32) \|\| (dailyAllowanceMs > 0 && không có override (I34) && usedToday ≥ dailyAllowanceMs))` (I19) | `dailyAllowanceMs` (0 = không có hạn mức ngày), `sessionMs`/`sessionBreakMs` (I32), `perDevice` (I33) |
| `SCHEDULE` | `enabled && scheduleActive && không có override (I34)` — lịch (ngày + khung giờ, có thể vắt qua nửa đêm, có thể nhiều khung, I30) chính là khoảng chặn (I20) | `schedule` |
| `LOCKDOWN` | `enabled && lockdownRemaining > 0` (I21) | `lockdownMs` |

Gộp nhiều block cùng phủ một target: xem I22. Một block có thể chừa ra vài host con của site nó
chặn (`exceptSites`, I31).

**Nhiều luật trên một block** (I44). Một block giữ được cùng lúc ba luật, luật nào cũng tùy chọn:

- **Giờ chặn** (`Block.blockedHours`, null = không có; dùng cho `HABIT` và `ALLOWANCE`): trong các
  giờ đó block chặn hẳn. Block `SCHEDULE` không dùng trường này: `schedule` của nó đã là giờ chặn.
- **Hạn mức** (`dailyAllowanceMs`, `sessionMs`/`sessionBreakMs`, `perDevice`, I19, I32, I33):
  block `ALLOWANCE` luôn có; block `HABIT` có khi được đặt (`Block.hasLimit`).
- **Habit** (`mode == HABIT`): máy trạng thái ở trên.

`mode` vẫn nói block có gì: có habit là `HABIT`, không habit mà có hạn mức là `ALLOWANCE`, chỉ có
giờ là `SCHEDULE`. `LOCKDOWN` đứng riêng, không ghép với luật nào. Ở block `HABIT`/`ALLOWANCE`,
`schedule` vẫn là lúc habit và hạn mức có hiệu lực; **giờ chặn không phụ thuộc `schedule`**.

Luật nào chặn thì block chặn. Habit chỉ mở được phần của habit:

| Luật | Chặn khi | Habit mở được? | Phút mua (I34)? |
|---|---|---|---|
| Giờ chặn | đang trong giờ chặn | không | được, nếu block mua được |
| Nghỉ bắt buộc (I32) | đang nghỉ | không | không |
| Hạn mức ngày | đã dùng ≥ `dailyAllowanceMs` | xem thứ tự dưới đây | được, nếu block mua được |
| Habit | `state != UNLOCKED` | có | không |

Trong lúc luật của chính block đang giữ chặt (giờ chặn, nghỉ bắt buộc, hạn mức ở habit trước), `Redeem`
bị từ chối (`RejectReason.HELD`) và credit giữ nguyên: mở lúc đó chỉ đốt phút và tốn `dailyCapMs` mà
không mở được gì.

Block `HABIT` có hạn mức ngày thì **thứ tự** chọn được theo từng block (`Block.habitFirst`):

- **Hạn mức trước** (`false`, mặc định): phút miễn phí đi trước. Chưa dùng hết thì block không đòi
  habit. Dùng hết thì block chặn, và habit mở lại được `reward.unlockDurationMs`.
- **Habit trước** (`true`): chặn tới khi xong habit, như block `HABIT` thường, và hạn mức giữ
  ở trên: dùng hết rồi thì habit cũng không mở được nữa.
- Không có hạn mức ngày (chỉ có luật liên tục) thì không có gì để "hết": tính như habit trước.

Block `HABIT` không mua phút được (I34), kể cả khi có hạn mức hay giờ chặn. Block `ALLOWANCE` có
giờ chặn mua được: phút mua mở cả giờ chặn lẫn hạn mức ngày, không mở nghỉ bắt buộc. Pause (I25)
nhấc mọi luật của block như trước.

`progress.accumulatedMs` của block `HABIT` là timer của habit, nên hạn mức của nó đếm riêng ở
`progress.limitUsedMs`; về `ARMED` không xóa số này, chỉ qua ngày mới xóa (I13). `HabitProgress`
đọc qua `limitUsed(block)`. Đổi mode thì số chuyển theo (`forMode`), cả khi nhận từ máy khác.
Timer không có `habitPackages` trên block có hạn mức chỉ đếm khi target đang bị chặn (I4): đang
dùng phút miễn phí không phải là đang chờ.

**Thiết bị** (`Block.devices`, I42): `BOTH` (mặc định), `PHONE` hay `LAPTOP`. Mỗi máy biết nó là
gì (`World.deviceKind`, của riêng máy, không chung; null = dữ liệu cũ, mọi block đều áp dụng). Ở máy
mà block không áp dụng, block **không chặn gì** ở đó (cả "đang chặn" ở bảng trên lẫn I22) và không
tính giờ ở đó (ALLOWANCE, I41, và timer HABIT đếm trên chính target). Riêng timer HABIT đếm trong app
khác (`habitPackages`, I16) vẫn đếm ở bất kỳ máy nào có app đó: credit là chung (§2.4), nên dùng app
học trên điện thoại vẫn mở được một block chỉ của laptop. Block vẫn được đồng bộ, hiện ra và mở khóa
được từ máy kia.

### 2.3 Sửa block: nới lỏng cần "guard"

Ngoài sự kiện, user còn sửa cấu hình block (lớp app gọi `WorldStore.edit`). Sửa nào làm block
**dễ vượt qua hơn** là *nới lỏng* và phải qua guard trước khi áp dụng: chờ đếm ngược (mặc định
60 giây, rời màn hình là hủy) với block `SOFT`, hoặc nhập mật khẩu hardcore với block
`HARDCORE`. Sửa chỉ làm chặt hơn thì áp dụng ngay. `BlockEdits.loosens(old, new)` là định nghĩa
chuẩn (bỏ target, giảm timer, `AND → OR`, đổi habit app, tăng thời gian mở khóa / TTL / hạn mức,
giảm độ dài lockdown, đổi lịch, `HARDCORE → SOFT`, đổi mode hay loại trigger). Ngoài ra cũng
là nới lỏng: xóa block, xóa một mục checklist, đổi `linkedDay`/`countsGoal` của habit item,
tăng `dailyCapMs`, và bắt đầu Pause (I25). Với nhiều luật (I44): bỏ hay đổi giờ chặn (kể cả
`schedule` của `SCHEDULE`), bỏ habit, thêm habit vào block có hạn mức theo thứ tự hạn mức trước
(thêm một đường vượt hạn mức), thêm hạn mức ngày vào block `HABIT` theo thứ tự hạn mức trước
(thêm phút miễn phí), và `habitFirst` từ `true` về `false`. Thêm giờ chặn, hay thêm hạn mức hoặc
habit vào block chỉ có giờ, thì chặt hơn. Với habit (`BlockEdits.habitLoosens`, chỉ khi có block
dùng nó): phần mở khóa (I36) nhỏ đi, đổi `kind`, hay tần suất `freqNum/freqDen` của habit `CHECK`
thấp đi (Loop tự điền nhiều ngày hơn). Xóa habit **không** nới lỏng: habit item của nó ở lại và
không bao giờ xong nữa (I36), muốn bỏ thì sửa block. Sửa habit ở xrnloops hay web app (§2.5) không
qua guard: đó là app theo dõi của chính user. Với các luật mới: thêm một `exceptSites`, tắt hay
tăng `sessionMs`, giảm `sessionBreakMs`, chuyển `perDevice` sang `true` (mỗi máy có nguyên hạn
mức), chuyển `selfPaced` sang `true` (mở khóa trải ra cả ngày, I41), bớt một thiết bị khỏi
`devices` (`BOTH → PHONE/LAPTOP`, `PHONE ↔ LAPTOP`; I42), đổi thẻ của tag item hay bỏ thẻ khỏi nó (I43), bỏ hay đổi một khung lịch (I30). Ở Settings: cụm từ override ngắn hơn, giảm
`overrideWaitMs`, tăng `overrideGrantMs` hay `overridesPerDay` (`BlockEdits.settingsLoosen`). Ngoại lệ duy nhất: lockdown **không đang chạy** thì
sửa/xóa tự do (`BlockEdits.guardNeeded`). Test: `BlockEditsTest`.

**Khóa có hạn** (I39): `block.lock = BlockLock(untilWall, setAtWall)`. Khi `now_wall < untilWall`
block **đang khóa** (`BlockEdits.locked`): mọi sửa nới lỏng **cấu hình của nó** (sửa block hay
checklist của nó, tắt, xóa, sửa nới lỏng một habit mà habit item của nó dùng) bị **từ chối**,
không chờ hay mật khẩu nào mở được. Sửa chặt hơn vẫn được. Khóa không đụng tới đường qua block mà
user đã đặt sẵn: làm habit, Pause (I25), override (I34) vẫn như cũ, và Settings chung
(`dailyCapMs`, override) vẫn qua guard thường. Đặt hay kéo dài khóa là chặt hơn; `untilWall` nhỏ
đi là nới lỏng, và lúc đang khóa chỉ làm được bằng **mở khẩn cấp** (I40). `lock` không bao giờ về
null: hết hạn thì ở lại (đã qua), không chặn gì. Đồng hồ là wall của mỗi máy (điện thoại:
ClockGuard đã bỏ chỉnh tay); laptop từ chối sửa nới lỏng block đang khóa theo đồng hồ của nó.

### 2.4 Nhiều thiết bị: trạng thái chung

Điện thoại và laptop dùng chung một trạng thái qua server đồng bộ (`xrnrule-sync`, chỉ là kho
tài liệu JSON có phiên bản, không có logic). Một lần redeem mở block trên **mọi** thiết bị tới
cùng một thời điểm; hạn mức mở khóa mỗi ngày, Pause và lockdown cũng là chung.

**Phần chung** (`SharedState.of(world)`): cấu hình block (bỏ `state`), mọi `ChecklistItem`,
credit, session, lockdown, `pause`, `lastPauseEpochDay`, `settings`, nhật ký mở khóa hôm nay
(`unlockLog`, I28), lượng ALLOWANCE đã dùng hôm nay và lượt dùng liên tục của từng thiết bị
(I19, I32), override đang chạy và nhật ký override hôm nay (I34), nhật ký mở khẩn cấp
(`emergencyLog`, I40). Chỉ thời gian
**wall** đi qua máy khác: mọi mốc monotonic bị đặt 0 khi chiếu và được neo lại khi nhận (như I8).
**Không** chung qua đây: habit và habit entry (đi qua habit server, §2.5), `done` của habit item
(mỗi máy tự suy từ entry của nó, I36; `spentEpochDay`/`spentAmount` thì chung), tiến độ timer
HABIT, foreground, `appliedShield`, override đang chờ (`pendingOverride`), focus run (`focus`, I37).

**Một vòng đồng bộ:** đọc bản của server → `SharedStateMerged(base, remote)` → engine trộn **ba
chiều** bản chiếu hiện tại với `remote`, so với `base` (bản hai bên đã thống nhất lần trước; null ở
lần đầu) → đẩy kết quả lên kèm `If-Match`; bị 412 thì làm lại. Khóa chỉ một bên đổi so với `base`
lấy bên đó (kể cả xóa — nên credit đã dùng ở máy kia không sống lại, I26). Hai bên đổi khác nhau:

| Khóa | Bên thắng |
|---|---|
| session | kết thúc muộn hơn; một bên xóa, bên kia thay bằng cái mới → cái mới; cùng một session (cùng `startedAtWall`) → `usedMs` lấy max theo từng thiết bị (I41) |
| credit | kiếm sớm hơn; một bên xóa (đã dùng / hết hạn) → xóa |
| lockdown | kết thúc muộn hơn; một bên xóa → bên còn |
| pause | bắt đầu sớm hơn; một bên null → bên còn; `lastPauseEpochDay` lấy lớn hơn |
| block | `editedAtWall` muộn hơn; sửa vs xóa → giữ bản sửa (chặt hơn) |
| checklist item | cấu hình theo bên đổi (remote nếu cả hai); `done` = OR; `spentEpochDay` = max; `spentAmount` theo `spentEpochDay` lớn hơn, cùng ngày → max; sửa vs xóa → giữ |
| settings | từng trường: `dailyCapMs`, `skewToleranceMs`, `overrideGrantMs`, `overridesPerDay` lấy nhỏ hơn; `unlockWarningMs`, `overrideWaitMs` lấy lớn hơn; `overridePhrase` lấy cái dài hơn; `showClock` lấy `false` |
| `unlockLog` | ngày lớn hơn thắng cả; cùng ngày → hợp, mỗi khóa lấy max |
| allowance (mọi block `hasLimit`, I44) | theo (block, thiết bị): ngày mới hơn; cùng ngày → `ms` lớn hơn, rồi `atWall` muộn hơn (bản ghi mang theo lượt liên tục, I32) |
| override | kết thúc muộn hơn; một bên xóa → bên còn (như lockdown) |
| `overrideLog` | như `unlockLog`: ngày lớn hơn thắng; cùng ngày → hợp |
| `block.lock` | trộn riêng, bất kể bản block nào thắng: `setAtWall` muộn hơn (đặt khóa và mở khẩn cấp đều đóng dấu lúc làm) (I39) |
| `emergencyLog` | hợp, không bao giờ bớt (I40) |

Bản ghi allowance chỉ được thêm, không bị xóa khi trộn, nên máy chủ của bản ghi phải tự thay nó: mỗi
máy **luôn** chiếu bản ghi của chính nó cho mọi block `hasLimit` có `progress`, kể cả khi chưa dùng
(0 ms), để ngày mới của nó thắng bản của hôm trước mà máy kia còn giữ. Khi nhận, bản ghi của chính
máy này trên server mà "lớn hơn" bản của nó (cùng thứ tự như bảng trên: ngày, `ms`, `atWall`; ví dụ
dữ liệu máy này bị mất) thì được nhận vào `progress` và `runs` của nó. Hai điều này để hai máy luôn
thống nhất được: không bao giờ có một bản ghi mà một máy cứ thêm lại và máy kia cứ bỏ đi, đẩy qua
lại mãi (đã xảy ra ngày 2026-11-05 với bản ghi 0 ms của laptop).

Nhận kết quả (I27): credit/session/lockdown/pause mới được neo monotonic theo wall; `state` của
block suy ra lại (có session → `UNLOCKED`, có credit → `EARNED`, không → `ARMED`; mode khác HABIT
luôn `ARMED`); rời EARNED/UNLOCKED theo cách này thì reset như I10; vào `UNLOCKED` theo cách này thì habit item mà máy đã redeem đã tiêu
(`spentEpochDay`/`spentAmount` đổi qua lần trộn) **không** tiêu thêm, còn item nó không tiêu (có thể
nó chưa có entry mới) thì tiêu ở đây như redeem (I24), để một lần tick không trả cho hai lần mở. Sau đó sweep chạy như mọi sự kiện. Test: `SharedStateTest` (bảng trên), I26–I28.

### 2.5 Habit server: danh sách habit chung

Habit và entry đi qua **habit server** (`xrnloops-sync`, trước là server của xrnloops), không qua
`xrnrule-sync`: mỗi habit và mỗi (habit, ngày) là một bản ghi riêng có `mtime` và `seq` của server,
và người viết sau thắng theo từng bản ghi. xrnloops và web app habit cũng ghi
vào đây theo cách đó. xrnrule thì ghi kèm `baseSeq` (bản nó dựa vào): server chỉ nhận nếu đó vẫn
là bản đang lưu, không thì trả bản mới hơn để trộn lại (I38). Server giữ các trường mà người viết
không gửi (`icon`, `timer` mà xrnloops không biết).

**Một vòng:** đọc các bản ghi mới hơn `World.habitSyncSeq` (theo trang) → `HabitServerPulled` (engine
trộn từng bản ghi, I38) → đẩy mọi bản ghi `dirty` kèm `baseSeq` → `HabitServerPushed` (bản được
nhận thì sạch, bản bị từ chối thì trộn lại) → còn `dirty` thì đẩy lại (có giới hạn số lần). Lớp
ngoài chọn lúc chạy; engine chỉ nhận hai sự kiện đó. Ngày gửi lên là ngày habit (`YYYY-MM-DD`).

**Server lùi lại** (khôi phục từ bản sao lưu): server có một `epoch` (số ngẫu nhiên, lưu cùng dữ liệu).
Máy gửi kèm `epoch` nó biết; máy nào đã đọc quá `seq` của server (`since > seq`) mà vẫn cùng `epoch` thì
server đổi `epoch` mới, nên **mọi** máy đều biết, kể cả máy mà `seq` server đã vượt lại (do máy khác gửi
lại bản bị mất). `World.habitServerEpoch` khác `epoch` server trả (hoặc server không có epoch mà trả `seq`
nhỏ hơn số máy này đã đọc) → lớp ngoài
đọc lại **toàn bộ** từ 0 rồi gửi một `HabitServerPulled(full = true)` với mọi bản ghi; engine trộn
như thường, trừ: bản ghi sạch mà máy này đã thấy ở `seq` lớn hơn bản server giờ có (server mất bản đó
khi khôi phục) → giữ bản ở máy này, `dirty`, `seq` = của server (để đẩy lại thắng), và bản ghi máy này
có mà server không có → `dirty`, `seq = 0` (đẩy lại); tombstone mà server không có habit → bỏ. Rồi
`habitSyncSeq = seq` của server (có thể nhỏ hơn trước). Giới hạn đã biết: habit xóa sau bản sao lưu
mà server đã nhận lệnh xóa thì hiện lại (máy không giữ tombstone đã gửi); xóa lại là xong. **Mất file entry ở máy này** (hỏng, bị xóa):
`habitSyncSeq = 0`, đọc lại hết.

---

## 3. Bất biến (mỗi cái = 1 test)

| # | Bất biến | Test skeleton |
|---|---|---|
| **I1** | `EarnedCredit.expiresAtWall == earnedAtWall + creditTtlMs` (mặc định 2h; dùng wall clock để sống qua reboot, mono chỉ để soát tamper như I3). Ở EARNED, khi `now_wall ≥ expiresAtWall` → engine tick đưa về ARMED. | `earn → advance clock 2h → tick → assert ARMED` |
| **I2** | Countdown UNLOCKED tính bằng `now_mono - session.startedAtMonotonic`, **không** dùng wall clock. | `redeem → giữ nguyên mono, đẩy wall +10h → tick → vẫn UNLOCKED` |
| **I3** | Nếu `(now_wall - session.startedAtWall) < (now_mono - session.startedAtMonotonic) - SKEW_TOLERANCE_MS` → tamper → UNLOCKED → ARMED ngay. `SKEW_TOLERANCE_MS = 5_000`. | `redeem → wall lùi 1h, mono +1phút → tick → ARMED, reason=TAMPER` |
| **I4** | `HabitProgress.accumulatedMs` chỉ tăng khi `foregroundPackage ∈ countedPackages` (= `trigger.habitPackages` nếu khác rỗng, không thì `block.targetPackages` — xem I16) **và** timer đang chạy, block đang ARMED. Shield đang hiện vẫn được cộng: TIMER không có `habitPackages` chính là "chờ N phút trước màn chặn". Khi app đếm giờ rời foreground → ngừng cộng. | `feed foreground events: target 10s, other 10s, target 10s → accumulated == 20s` |
| **I5** | Trong lúc ARMED (kể cả khi timer đang chạy / đang tick to-do) `shieldState(block) == ON`. | `mọi trạng thái ARMED/EARNED → shieldOn == true; chỉ UNLOCKED → false` |
| **I6** | `isAppUnlocked(pkg) == blocks.any { pkg ∈ it.targets && it.state == UNLOCKED }`. | `2 block cùng phủ pkg, 1 UNLOCKED 1 ARMED → isAppUnlocked == true` |
| **I7** | Engine không bao giờ trả về "shield changed" trước khi `PersistPort.save()` được gọi cho state mới. Áp dụng cho **cả tick/untick ChecklistItem**. | test dùng fake `PersistPort` ghi lại thứ tự call; assert save trước shield-callback |
| **I8** | `rebuild(fromStorage)` tái tạo đúng trạng thái + tính lại credit/session theo mono hiện tại; `shieldPlan()` được gọi *trước* khi phát signal "UI ready". | `persist EARNED gần hết hạn → rebuild sau 2h1phút → ARMED` |
| **I9** | `redeem` bị từ chối nếu `committed + amount > dailyCapMs` (mặc định 60 phút), với `committed = usedToday + Σ durationMs` của mọi session **đang mở** (mỗi session đang mở giữ chỗ trọn độ dài của nó, kể cả session tự điều tiết I41: không thế thì mở nhiều block cùng lúc sẽ vượt hạn mức), và `usedToday = dailyUnlockUsedMs + Σ unlockLog` của ngày `dailyUsageEpochDay` (0 nếu là ngày khác; `dailyUnlockUsedMs` chỉ còn cho dữ liệu cũ, xem I28). Block ở lại EARNED. | `dailyUsed=58phút, credit=5phút, cap=60 → redeem → vẫn EARNED, reason=CAP_REACHED`; `i09_openUnlocksCountTowardCap`: ba block 30 phút, cap 60, mở hai cái → cái thứ ba bị từ chối |
| **I10** | Bất kỳ transition nào về ARMED → `HabitProgress` reset (0 / windowStart=today) **và** mọi `ChecklistItem.done=false`, `doneAtMonotonic=null`. | `checklist 3/3 done → EARNED → creditExpired → ARMED → assert 0/3 done` |
| **I11** | CHECKLIST đủ ngưỡng ⟺ `doneCount ≥ (requiredAmount==-1 ? size : requiredAmount)` và `size > 0`. Checklist rỗng không bao giờ đủ ngưỡng. | bảng: (size,req,done)→expected; gồm ca size=0 |
| **I12** | `toggleItem(itemId, done)` khi block ở EARNED hoặc UNLOCKED là **no-op** (không đổi state, không đổi `done`). Chỉ có tác dụng ở ARMED. (Habit item không toggle được; bỏ tick habit ở EARNED thì xem I36.) | `EARNED → toggleItem(false) → item vẫn done, state vẫn EARNED` |
| **I13** | `windowStart` của `HabitProgress` là **đầu ngày local** (00:00 theo `ZoneId` thiết bị). "Day rollover" so sánh `localDate(now_wall) != localDate(windowStart)`. | `windowStart = hôm qua 23:59 → tick lúc 00:01 hôm nay → ARMED + reset` |
| **I14** | Nếu `trigger.type == CHECKLIST` mà block **không có** ChecklistItem nào → block coi như **không hợp lệ để ARMED-đủ-ngưỡng**; UI phải chặn tạo block kiểu này. Engine: `triggerMet` trả false. | `checklist rỗng → triggerMet == false mãi mãi` |
| **I15** | `trigger.type == COMBINED`: `triggerMet` theo `combineMode` — `AND` cần `timerMet && checklistMet`, `OR` cần `timerMet \|\| checklistMet`, bất kể thứ tự hoàn thành (tick checklist trước hay chạy hết timer trước đều được). Cả hai đường dẫn hoàn thành (toggle checklist item, timer tick) đều phải gọi `triggerMet` theo cùng công thức này. | `i15_combinedAndRequiresBoth`: chỉ xong checklist hoặc chỉ xong timer → vẫn ARMED; xong cả hai (theo bất kỳ thứ tự nào) → EARNED. `i15_combinedOrRequiresEither`: xong một trong hai → EARNED ngay. |
| **I16** | Khi `trigger.habitPackages` khác rỗng, timer đếm giờ theo tập này thay vì `block.targetPackages` — app Target vẫn bị chặn như thường, nhưng ở trong Target **không** cộng giờ; phải ở trong một app thuộc `habitPackages` mới cộng. Khi `habitPackages` rỗng, hành vi quay về đếm giờ trong `targetPackages` (tương thích ngược). | `i16_habitPackagesOverridesTargetForAccrual`: habitPackages={"com.habit"}, ở trong target 10s → accumulated==0; chuyển sang com.habit 10s → accumulated==10s. `i16_emptyHabitPackagesFallsBackToTarget`: habitPackages rỗng → hành vi y hệt TIMER cũ. |
| **I17** | `block.targetPackages` (và `trigger.habitPackages`) có thể chứa nhiều hơn một package; mọi test thành viên (shield, accrual) là OR trên tập — ở trong *bất kỳ* app nào trong tập đều tính. | `targetPackages={"a","b"}` → cả hai đều bị chặn cùng lúc bởi một block; foreground chuyển a→b→a cộng dồn giờ ở cả hai. |
| **I18** | Website target: `ShieldPlan.blockedSites` chứa các tên miền target đang bị chặn (tính như package, theo I6/I22). Foreground "ở trong target" ⟺ `foregroundPkg ∈ targetPackages` **hoặc** `foregroundSite` khớp (theo luật hậu tố ở §1) một phần tử của `targetSites`. Mọi accrual dùng đúng phép thử này. | `i18_siteTargetBlocksAndMatchesSubdomains`: block site `youtube.com` → `blockedSites` chứa nó; `m.youtube.com` khớp, `notyoutube.com` không khớp. `i18_timerAccruesOnTargetSite`: TIMER chặn `youtube.com`, foreground là trình duyệt ở `m.youtube.com` 10s → accumulated == 10s; cùng trình duyệt ở site khác → không cộng. |
| **I19** | `ALLOWANCE`: thời gian foreground trong target được cộng vào `progress.accumulatedMs` của ngày local hiện tại, **chỉ khi target đó không đang bị chặn** (không trừ hạn mức khi đang nhìn shield), và không vượt quá `dailyAllowanceMs`. Đang chặn ⟺ đã dùng ≥ hạn mức, với "đã dùng" = phần của máy này **cộng** phần các thiết bị khác đã báo cho cùng ngày (`otherAllowance`, §2.4). Qua ngày mới (I13) → về 0, hết chặn. | `i19_allowanceBlocksOnceUsedUp`: hạn mức 10 phút, ở target 9 phút → chưa chặn; thêm 2 phút → chặn, accumulated == 10 phút. `i19_allowanceResetsAtDayRollover`. `i19_allowanceCountsOtherDevices`: máy khác đã dùng 7 phút hôm nay, máy này 4 phút → chặn; phần của hôm qua không tính. `i19_aStaleUseOfThisDeviceSettles`: bản ghi hôm qua của laptop còn ở điện thoại, laptop sang ngày mới chưa dùng → sau vài vòng không máy nào còn phải ghi. `i19_thisDevicesUseComesBackFromTheServer`: laptop mất tiến độ hôm nay → nhận lại phần đã dùng từ server, rồi hai bên ngừng ghi. |
| **I20** | `SCHEDULE`: đang chặn ⟺ `scheduleActive` (ngày trong `activeDays` và giờ trong `activeWindow`; window có `start > end` là vắt qua nửa đêm). Window vắt qua nửa đêm **thuộc về ngày nó bắt đầu**: phần sau 00:00 xét ngày hôm trước có trong `activeDays` không. Không có cách mở khóa; chỉ thời gian trôi. | `i20_scheduleBlocksOnlyInsideWindow`: window 09:00–17:00 → 08:59 không chặn, 09:00 chặn, 17:00 hết chặn; window 22:00–06:00 chặn lúc 23:00 và 05:00. `i20_overnightWindowBelongsToItsStartDay`: chỉ Thứ Sáu 23:00–07:00 → chặn Thứ Bảy 02:00, không chặn Thứ Sáu 02:00. |
| **I21** | `LOCKDOWN`: `StartLockdown` tạo `Lockdown{startedAtMonotonic=now_mono, startedAtWall=now_wall, durationMs=block.lockdownMs}` (no-op nếu đang chạy). Còn lại = `durationMs − (now_mono − startedAtMonotonic)` — chỉnh **wall** clock không rút ngắn được. Hết giờ → sweep xóa Lockdown, hết chặn. Rebuild (I8) re-anchor `startedAtMonotonic` từ delta wall như UnlockSession. | `i21_lockdownIgnoresWallClockAndEnds`: start 1h → đẩy wall +5h, mono +1 phút → vẫn chặn; mono tới 1h → hết chặn, Lockdown bị xóa. |
| **I22** | Gộp: target bị chặn ⟺ có block `ALLOWANCE`/`SCHEDULE`/`LOCKDOWN` phủ nó **đang chặn**, **hoặc** (có block `HABIT` phủ nó, không cái nào `UNLOCKED`, và ít nhất một cái `enabled && scheduleActive`). Unlock của HABIT **không** gỡ được chặn của các mode kia. I6 vẫn đúng cho phần HABIT. | `i22_habitUnlockDoesNotLiftStrictBlock`: cùng target, HABIT ở UNLOCKED + SCHEDULE đang trong giờ → vẫn chặn; ngoài giờ → không chặn. |
| **I23** | Habit item (`habitId != null`): `ToggleChecklistItem` trên nó là **no-op**. Ngày của item `d` = ngày habit hôm nay (`TODAY`) hoặc hôm qua (`YESTERDAY`). Lượng của item trong ngày `d`: habit `CHECK` → 1 nếu entry ngày `d` là `YES_MANUAL`, hoặc `countsGoal` và luật tự điền của Loop (`LoopRules.computeEntries`, chỉ với `freqNum/freqDen < 1`) cho `YES_AUTO` ở ngày `d`; không thì 0 (`SKIP` = 0). Habit số `AT_LEAST` → `max(0, value)` cộng phần đang chạy của focus run nếu run thuộc ngày `d` (I37). `AT_MOST` → không bao giờ xong. Sweep đổi linked item cũ (`externalHabitId`) thành habit item: `habitId = externalHabitId`, giữ `linkedDay`, `countsGoal`, `spentEpochDay` (`spentAmount = 1` nếu có). | `i23_manualToggleIgnoredOnHabitItem`, `i23_yesterdayItemCountsYesterdaysTick`, `i23_goalItemCountsAutomaticTicks`, `i23_dayStartsAtTheTrackersHour`, `i23_linkedItemsBecomeHabitItems`. |
| **I24** | Redeem (`EARNED → UNLOCKED`) tiêu đúng **một phần mở khóa** `u` (I36) của mọi habit item có `available ≥ u`, tính từ entry chứ không theo `done` (block nhận `EARNED` từ máy khác chưa từng được tính item ở máy này): `spentAmount = spentOn(d) + u`, `spentEpochDay = d` (ngày của item, I23); phần dư tính cho lần sau. Ghi lúc redeem chứ **không** lúc earn: credit hết hạn (I1) hoặc user hủy khi chưa dùng thì chưa tiêu gì, kiếm lại được ngay. Về ARMED (I10) giữ `spentEpochDay`/`spentAmount`. Sang ngày habit mới thì kiếm lại được; tick buổi tối không trả lại lần nữa sau nửa đêm nếu ngày habit chưa sang. | `i24_checkOpensEachBlockOncePerDay`, `i24_expiredCreditDoesNotSpend`, `i24_nextDayCanEarnAgain`, `i24_eveningTickDoesNotPayAgainAfterMidnight`, `i24_yesterdayItemSpendsYesterdaysTick`, `i24_redeemSpendsOneUnitAndKeepsTheRest`. |
| **I25** | `StartPause(durationMs)` tạo `Pause{startedAtMonotonic, startedAtWall, durationMs}` và ghi `lastPauseEpochDay = today`; no-op nếu đang nghỉ hoặc hôm nay đã nghỉ. Trong lúc nghỉ, các block `SOFT` không phải `LOCKDOWN` coi như không chặn (bỏ khỏi phép gộp I22); block `HARDCORE` và lockdown vẫn chặn. Còn lại = `durationMs − (now_mono − startedAtMonotonic)` như I21; hết giờ → sweep xóa `Pause`; `EndPause` xóa ngay. Rebuild (I8) re-anchor như Lockdown. Accrual ALLOWANCE vẫn cộng lúc nghỉ (đó là dùng thật). | `i25_pauseLiftsSoftBlocksOnly`, `i25_pauseEndsAndIsOncePerDay`, `i25_pauseSurvivesRebootViaWallDelta`. |
| **I26** | `SharedStateMerged(base, remote)` trộn ba chiều (§2.4): khóa chỉ một bên đổi so với `base` lấy giá trị bên đó, kể cả khi bên đó **xóa** khóa; hai bên đổi khác nhau → theo bảng §2.4. Credit đã dùng hay hết hạn ở một máy không bao giờ sống lại vì máy kia chưa biết. | `i26_creditUsedElsewhereIsNotResurrected`: base có credit, remote đã redeem (bỏ credit, thêm session) → máy này UNLOCKED, không còn credit. `i26_offlineDoubleRedeemKeepsLaterSession`. `i26_sessionEndedElsewhereEndsHere` (vd máy kia phát hiện tamper). |
| **I27** | Nhận trạng thái chung: mốc monotonic mới = `now_mono − (now_wall − startedAtWall)` (không báo tamper giả); `state` suy từ session/credit; rời EARNED/UNLOCKED → reset như I10; vào UNLOCKED → habit item mà máy đã redeem đã tiêu thì giữ đúng phần đó, item nó không tiêu thì tiêu ở đây như redeem (I24); `done` của habit item **không bao giờ** lấy từ máy khác. | `i27_adoptedSessionRunsOutOnTime`, `i27_adoptedUnlockSpendsHereWhatTheRedeemerDidNot`, `i27_habitItemDoneIsNeverShared`. |
| **I28** | Thời gian mở khóa đã dùng hôm nay là `unlockLog`: session kết thúc (hết giờ hay tamper) ghi `unlockLog["blockId@startedAtWall"] = consumed`, nên hai máy cùng kết thúc một session chung chỉ tính **một lần**. Qua ngày mới thì xóa. | `i28_sharedSessionCountsOnceTowardCap`: hai máy đều kết thúc session 5 phút → trộn → `usedToday == 5 phút`. |
| **I29** | `block.targetDesktopApps` là target như `targetPackages`: nằm trong `blockedPackages`, foreground khớp thì bị chặn, ALLOWANCE/timer cộng giờ, và bỏ một desktop app là nới lỏng (§2.3). | `i29_desktopAppTargetsActLikePackages`. |
| **I30** | `Schedule.moreWindows`: thêm khung giờ, mỗi khung có ngày riêng. `scheduleActive` ⟺ khung chính (`activeDays` + `activeWindow`) **hoặc** bất kỳ khung nào trong `moreWindows` đang active, mỗi khung theo luật I20 (vắt qua nửa đêm thuộc về ngày bắt đầu). | `i30_anyScheduleWindowCounts`: 00:00–14:00 mọi ngày + 18:00–22:00 mọi ngày → chặn 10:00 và 19:00, không chặn 15:00 và 23:00. |
| **I31** | `block.exceptSites`: host khớp (luật hậu tố §1) một phần tử của `exceptSites` thì block đó **không phủ** host đó: không chặn, không cộng giờ, kể cả khi host cũng khớp `targetSites`. `ShieldPlan.siteExceptions[domain]` = giao các `exceptSites` của mọi block đang chặn domain đó; `covers(site)` ⟺ có domain chặn khớp site mà không exception nào của domain đó khớp. | `i31_exceptedHostIsNeitherBlockedNorCounted`: ALLOWANCE `youtube.com` trừ `music.youtube.com` đã hết hạn mức → `m.youtube.com` bị chặn, `music.youtube.com` không; ở `music.youtube.com` 5 phút không cộng giờ. |
| **I32** | Luật liên tục (`ALLOWANCE`, `sessionMs > 0`): mỗi máy giữ `RunUse(ms, lastActiveWall, breakUntilWall)` trong `runs`. Accrual (cùng điều kiện như I19): nếu `now_wall − lastActiveWall ≥ sessionBreakMs` thì `ms` về 0 trước khi cộng (nghỉ đủ lâu = đã nghỉ, kể cả nghỉ tự nguyện); cộng delta; `lastActiveWall = now_wall`. "Liên tục đã dùng" = `ms` của máy này + (nếu không `perDevice`) `ms` của máy khác có `now_wall − lastActiveWall < sessionBreakMs`. Khi ≥ `sessionMs` → `breakUntilWall = now_wall + sessionBreakMs`. Block chặn khi `now_wall < breakUntilWall` của máy này, hoặc của máy khác nếu không `perDevice`. Override (I34) **không** gỡ được lúc nghỉ bắt buộc. `dailyAllowanceMs = 0` ⟺ không có hạn mức ngày, chỉ có luật liên tục. | `i32_rowRuleForcesABreak`: 15 phút liên tục / nghỉ 15 → phút 15 bị chặn, 15 phút sau mở lại với lượt mới. `i32_restingLongEnoughStartsAFreshRow`. `i32_rowCountsAcrossDevices`. |
| **I33** | `block.perDevice`: hạn mức ngày (I19) và luật liên tục (I32) chỉ tính phần của máy này; nghỉ bắt buộc của máy khác không chặn ở đây; override (I34) chỉ mở trên máy đã mua (`OverrideGrant.deviceId`). Mặc định `false`: các máy dùng chung. | `i33_perDeviceLimitIgnoresOtherDevices`. |
| **I34** | Override (mua thêm phút): `BeginOverride(blockIds)` chỉ nhận khi **mọi** block đang chặn target là `SOFT`, mode `ALLOWANCE`/`SCHEDULE`, không đang nghỉ bắt buộc (I32), và còn lượt hôm nay (`overridesPerDay` mỗi block, đếm qua `overrideLog` chung); ghi `pendingOverride` (chỉ ở máy này). `ClaimOverride(phrase)` nhận khi đã chờ ≥ `overrideWaitMs` theo monotonic và chưa quá `overrideWaitMs + 10 phút`, cụm từ khớp `overridePhrase` (bỏ dấu, không phân biệt hoa thường, gộp khoảng trắng), và điều kiện trên vẫn đúng; tạo `OverrideGrant` cho từng block (`durationMs = overrideGrantMs`, đếm monotonic như lockdown, re-anchor như I8/I27) và ghi `overrideLog["blockId@startedAtWall"]`. Lúc grant còn, block coi như không chặn (trừ nghỉ bắt buộc). Qua ngày mới `overrideLog` về rỗng. | `i34_overrideNeedsTheWaitAndThePhrase`, `i34_overrideLiftsLimitButNotABreak`, `i34_overridesPerDayAreShared`, `i34_habitOrLockdownIsNotOverridable`. |

| **I35** | Ghi habit (ngày habit, §1). `LogHabit(habitId, amount)`: habit không có → no-op. Habit số: entry hôm nay = `max(0, value cũ + amount)` (chưa có hay `UNKNOWN` = 0). Habit `CHECK`: `amount > 0` → `YES_MANUAL`, `amount < 0` → `NO`. `SetHabitEntry(habitId, epochDay, value, notes)`: đặt entry của một ngày bất kỳ `≤` hôm nay (habit `CHECK`: `NO`/`YES_MANUAL`/`SKIP`/`UNKNOWN`; habit số: `≥ 0`). Không đổi gì (cùng `value` và `notes`) → no-op. Mọi lần ghi ở máy này: `mtime = now_wall`, `dirty = true`, và lần ghi đầu tiên kể từ lần đồng bộ trước giữ `baseValue` = giá trị trước đó (I38). Không bao giờ tỉa: lịch sử là của biểu đồ. | `i35_logAddsToTodaysEntry`, `i35_checkLogsYesOrNo`, `i35_undoNeverGoesBelowZero`, `i35_setEntryEditsAPastDay`, `i35_writesAreMarkedForSync`. |
| **I36** | Phần mở khóa `u` của habit: `CHECK` → 1; habit số `AT_LEAST` → `⌈goal / freqDen⌉` (ít nhất 1); `AT_MOST` → không mở. Sau mọi sự kiện (sau sweep), với mọi block `HABIT` đang `ARMED`, mỗi habit item có `available = lượng(d) − spentOn(d)` (I23) và `done = habit còn && available ≥ u`; rồi kiểm `triggerMet` (I11/I15) → `EARNED`. Habit bị xóa → item ở lại và không bao giờ xong. Vậy habit `CHECK` mở mỗi block một lần mỗi ngày; habit số thì làm thêm một `u` nữa để mở lại. Ghi ở máy kia (qua habit server, I38) cũng làm block ở đây `EARNED`. Ở `EARNED` cũng tính lại `done` của habit item; habit item **đã xong ở máy này** mà không còn đủ lượng (bỏ tick, bớt lượng, ở đâu cũng vậy) và trigger không còn đạt → về `ARMED` như user hủy (I10), rồi tính lại habit item. Không vậy thì bỏ tick xong vẫn mở được, mà không tiêu gì (I24), rồi tick lại kiếm thêm lần nữa. Chỉ khi chính máy này thấy item từ xong thành chưa xong: block nhận `EARNED` từ máy khác lúc entry ở đây chưa tới thì không bị lấy lại. | `i36_habitItemEarnsWhenGoalMet`, `i36_weeklyGoalOpensPerDaysShare`, `i36_atMostHabitNeverOpens`, `i36_deletedHabitKeepsTheBlockClosed`, `i36_adoptedUnlockDoesNotSpendAgain`, `i36_otherDevicesWritesCount`, `i36_untickAfterEarnTakesTheEarnBack`, `i36_untickDoesNotTakeBackWhatOtherItemsStillEarn`, `i36_adoptedEarnIsNotTakenBackBeforeTheEntriesArrive`. |
| **I37** | Focus run (chỉ habit `TIMER`, chỉ ở máy này): `StartFocus(habitId)` — run của habit khác đang có thì fold rồi bỏ; run của cùng habit đang dừng thì chạy tiếp; đang chạy thì no-op. Đang chạy: `FocusRun{habitId, epochDay = ngày habit, startedAtMonotonic=now_mono, startedAtWall=now_wall, running=true}`; phần đang chạy = `(now_mono − startedAtMonotonic) / 60` phần nghìn phút (monotonic: chỉnh giờ không ăn gian được). **Fold** = ghi phần đang chạy vào entry ngày `epochDay` của run như `LogHabit` (I35) rồi dời `startedAtMonotonic` tới đúng phần đã ghi (phần lẻ dưới 60 ms ở lại). `PauseFocus(breakMs)`: fold, `running=false`; `breakMs > 0` → `resumeAtWall = now_wall + breakMs`, sweep tự chạy lại khi tới giờ. `EndFocus`: fold rồi xóa run. Sweep: run đang chạy mà `epochDay` khác ngày habit → fold vào ngày của nó, đoạn mới thuộc ngày mới. Rebuild (I8) re-anchor `startedAtMonotonic` từ delta wall như session. Phần đang chạy tính vào lượng (I23), nên block có thể `EARNED` giữa lúc chạy. | `i37_focusCountsMonotonicAndFoldsOnPause`, `i37_focusEarnsWhileRunning`, `i37_breakResumesByItself`, `i37_focusSurvivesReboot`, `i37_focusAcrossMidnightFoldsIntoItsDay`, `i37_onlyTimerHabitsFocus`. |
| **I38** | Trộn với habit server (§2.5). `HabitServerPulled(habits, entries, seq, dayStartHour)`: mỗi bản ghi entry — ở máy này không có hay không `dirty` → lấy bản server (`dirty = false`). `dirty` và habit là số → `value = max(0, server + (local − baseValue))` (phần đã thêm ở máy này cộng lên bản mới; `baseValue` null = 0), `baseValue = server`, `notes` theo `mtime` muộn hơn, vẫn `dirty`. `dirty` và `CHECK` → `mtime` muộn hơn thắng (máy này thắng thì vẫn `dirty`, `baseValue = server`). Mọi trường hợp: `seq = seq server`. Habit: như entry `CHECK`, `editedAtWall` so với `mtime`; server báo xóa → bỏ habit (trừ khi máy này đã sửa sau đó); habit xóa ở máy này (`habitTombstones`) mà server đã có bản sửa mới hơn bản nó dựa vào → sửa thắng xóa, habit trở lại. Rồi `habitSyncSeq = seq`, `habitDayStartHour = dayStartHour`. `HabitServerPushed(sent, accepted, rejected)`: bản được nhận mà ở máy này **chưa đổi** từ lúc gửi → `dirty = false`, `baseValue = null`, `seq` mới (tombstone thì bỏ); đã đổi tiếp → giữ `dirty`, `seq` mới, `baseValue = giá trị đã gửi`. Bản bị từ chối → trộn như `HabitServerPulled`. `habitServerEpoch = epoch` khi server có. `full = true` (server lùi lại, §2.5): bản sạch mà máy này đã thấy ở `seq` lớn hơn → giữ bản này, `dirty`; bản server không có → `dirty`, `seq = 0`; tombstone server không có → bỏ; `habitSyncSeq = seq` (không lấy max). | `i38_serverRecordsAreTakenWhenNothingChangedHere`, `i38_amountsAddedOnTwoDevicesAddUp`, `i38_laterCheckWins`, `i38_pushCleansOnlyWhatWasSent`, `i38_rejectedWriteIsRebased`, `i38_editBeatsDelete`, `i38_dayStartHourComesFromTheServer`, `i38_restoredServerGetsBackWhatItLost`. |
| **I39** | Khóa có hạn (§2.3): `BlockEdits.locked(block, nowWall)` = `lock != null && nowWall < lock.untilWall`. `BlockEdits.loosens` coi `lock.untilWall` nhỏ đi (kể cả bỏ khóa) là nới lỏng. `BlockEdits.lockedBy(world, blocks, nowWall)` = khóa đang chạy dài nhất trong các block đó, hay null. Trộn (§2.4): `lock` lấy theo `setAtWall` muộn hơn (null cũ nhất), độc lập với bản block thắng. | `i39_lockMergesByWhenItWasSet` (+ `BlockEditsTest`: rút ngắn khóa là nới lỏng, `locked` theo đồng hồ). |
| **I40** | Mở khẩn cấp: `BlockEdits.emergencyUnlock(world, blockId, nowWall)` trả `null` (từ chối) nếu block không đang khóa hay `emergencyLog` đã có `Defaults.EMERGENCY_UNLOCKS` (5) mục; không thì đặt `lock = BlockLock(nowWall, nowWall)` và thêm `emergencyLog["blockId@nowWall"] = nowWall`. 5 lần cho mọi block, mọi thiết bị, không hồi lại; `emergencyLog` chung (§2.4, hợp). | `i40_fiveEmergencyUnlocksInAll`, `i40_emergencyLogIsShared`. |
| **I41** | Mở khóa tự điều tiết (`Block.selfPaced`, chỉ HABIT; "Self paced" của Habits First): redeem tạo session `selfPaced = true`, và thời gian của nó chỉ trôi khi **đang dùng**: mỗi lần tích lũy (như I19), nếu foreground là target của block (I18) và không bị block khác chặn, `delta` monotonic từ mốc `markMonotonic` được cộng vào `session.usedMs[deviceId]`. `markMonotonic` là của máy này: đặt `now_mono` lúc redeem và lúc nhận một session mới từ máy khác; bỏ (null) khi chiếu (§2.4) và khi neo lại (I8), không có mốc thì lần tích lũy đó chỉ đặt mốc. Hết khi `Σ usedMs ≥ durationMs` **hoặc** khi sang ngày mới (`startedAtWall` trước đầu ngày hôm nay); `consumed = min(Σ usedMs, durationMs)` vào `unlockLog` (I28). Không kiểm tamper I3 (wall không dùng để đếm). Cảnh báo sắp hết khi lượt dùng còn lại ≤ `unlockWarningMs`. Trộn (§2.4): cùng session ở hai bên → `usedMs` lấy max theo từng thiết bị, nên thời gian dùng ở hai máy cộng lại. Đổi `false → true` là nới lỏng (§2.3). | `i41_selfPacedCountsOnlyUse`, `i41_selfPacedEndsWhenUsedUp`, `i41_selfPacedEndsWithTheDay`, `i41_selfPacedSurvivesARebootWithoutCountingIt`, `i41_selfPacedUseAddsUpAcrossDevices` (dùng xong ở một máy thì máy kia không mở lại), `i41_turningSelfPacedOnLoosens`. |
| **I42** | `World.appliesHere(block)` = `block.devices.include(deviceKind)`: `deviceKind == null` (dữ liệu cũ), `devices == BOTH`, hoặc `devices` khớp `deviceKind`. Block không áp dụng ở máy này: `isBlocking == false`, không nằm trong `blockers` (I22) nên không vào `ShieldPlan`, `timeLeft` bỏ qua nó, và không tích lũy ALLOWANCE (I19), lượt dùng I41 hay timer HABIT đếm trên target (I4); timer HABIT có `habitPackages` (I16) vẫn đếm. `BlockEdits.loosens`: `devices` mới không phủ hết thiết bị của cái cũ là nới lỏng. | `i42_laptopOnlyBlockDoesNothingOnThePhone`, `i42_phoneOnlyBlockStillBlocksThePhone`, `i42_aHabitAppElsewhereStillEarnsIt`, `i42_aTargetTimerOnlyCountsWhereTheBlockHolds`, `i42_droppingADeviceLoosens`. |
| **I43** | Tag item (`tag != null`): `ToggleChecklistItem(done = true)` trên nó là **no-op**; bỏ tick (`done = false`) thì được (khó hơn, không nới lỏng). `TagScanned(tag)`: với mọi block `HABIT` đang `ARMED`, mọi tag item chưa xong có `tag` đó → `done = true`; rồi kiểm `triggerMet` (I11/I15) → `EARNED`. Block ở trạng thái khác không đổi. App đăng ký đọc thẻ (`TECH_DISCOVERED`), nên chạm thẻ ở đâu (kể cả lúc shield đang hiện) cũng tính, không cần mở app. Lưu thẻ: đọc hai lần cùng `Tags.id` (thẻ ngân hàng, hộ chiếu đổi UID mỗi lần đọc). | `i43_aTagItemTicksOnlyFromItsTag`, `i43_oneScanTicksEveryBlockWaitingForIt`, `i43_aDifferentTagLoosens`. |
| **I44** | Một block giữ được giờ chặn, hạn mức và habit cùng lúc (§2.2); luật nào chặn thì block chặn, và `blockers` (I22) tính phần của habit riêng: phần giữ chặt (giờ chặn, nghỉ bắt buộc, hạn mức ở habit trước) không có block `HABIT` nào mở được, phần đòi habit thì theo I6. `Redeem` bị từ chối khi luật của chính block đang giữ chặt. Block gộp chặn y như hai block nó thay thế. | `i44_blockedHoursAddToALimit`, `i44_aMergedBlockBlocksLikeTheTwoItReplaces`, `i44_aHabitNeverOpensBlockedHours`, `i44_nothingIsCashedInWhileItsBlockIsHeld`, `i44_limitFirstGivesTheFreeMinutesThenTheHabitOpensIt`, `i44_habitFirstBlocksUntilTheHabitAndTheLimitHolds`, `i44_aRowRuleAloneActsLikeHabitFirst`, `i44_aForcedBreakHoldsThroughAnUnlock`, `i44_theLimitAndTheHabitTimerCountApart`, `i44_goingBackToArmedKeepsTheLimitUse`, `i44_theLimitsCountIsOnlyBookkeeping`, `i44_aWaitTimerDoesNotCountDuringFreeMinutes`, `i44_boughtMinutesLiftHoursAndLimit`, `i44_aLimitOnAHabitBlockIsShared`, `i44_changingModeMovesTheCount`. |

Hằng số mặc định (cấu hình qua Settings, nhưng đây là default hợp đồng):

```
creditTtlMs        = 2 * 60 * 60 * 1000      // I1
dailyCapMs         = 60 * 60 * 1000          // I9
skewToleranceMs    = 5_000                   // I3
unlockWarningMs    = 30_000                  // cảnh báo trước khi cắt (G3)
overridePhrase     = "I choose to keep scrolling"   // I34
overrideWaitMs     = 45_000                  // I34
overrideGrantMs    = 10 * 60 * 1000          // I34
overridesPerDay    = 2                       // I34, mỗi block
focusBreakMs       = 5 * 60 * 1000           // I37, nút "Nghỉ 5 phút"
```

---

## 4. Cổng (ports) — `:domain` định nghĩa interface, Android hiện thực

```
interface Clock {
    fun nowMonotonicMs(): Long     // SystemClock.elapsedRealtime()
    fun nowWallMs(): Long          // System.currentTimeMillis()
    fun zone(): ZoneId
}

interface PersistPort {
    fun loadWorld(): World                       // toàn bộ blocks + progress + credits + sessions + lockdowns
    fun save(world: World)                       // atomic, cả World; phải xong trước khi engine đụng shield
}

interface ShieldPort {
    fun apply(plan: ShieldPlan)                  // ShieldPlan(blockedPackages, blockedSites) — trạng thái mong muốn đầy đủ
}

interface Telemetry {                            // cho Insights; khi bật sync, còn gửi lên server của người dùng (trang Review)
    fun onBlockedImpression(pkg: String, atWallMs: Long)
    fun onHabitCompleted(blockId: String, atWallMs: Long)
    fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long)  // startedAtWall: tên phiên (I28), các thiết bị cùng phiên ghi cùng một tên
}
```

`BlockEngine` là hàm thuần trên `World` + sự kiện + `Clock`; nó **không** tự gọi `ShieldPort`.
Nó trả về `Outcome(newWorld, shieldPlan?, effects[])`. `shieldPlan != null` ⟺ phép chiếu chặn
hiện tại khác `World.appliedShield` (plan đã phát lần trước, được ghi lại trong World) hoặc là
`BootOrColdStart`. So với plan đã phát — không so với phép chiếu của World cũ — vì
`SCHEDULE`/`LOCKDOWN`/ngày mới đổi kết quả chặn chỉ bằng thời gian trôi. Lớp Android:
1. `PersistPort.save(outcome.world)`
2. nếu `outcome.shieldPlan != null` → `ShieldPort.apply(...)`
3. phát `outcome.effects` (notification cảnh báo 30s, telemetry, ...).

Thứ tự 1→2 là hiện thực của **I7**.

---

## 5. Sự kiện đầu vào của engine

```
sealed interface BlockEvent {
    data class ForegroundChanged(val pkg: String?, val site: String? = null) : BlockEvent  // app + host trình duyệt đang mở
    data object Tick                                         : BlockEvent   // ~1s khi có timer/session/mode ≠ HABIT
    data class ToggleChecklistItem(val itemId: String, val done: Boolean) : BlockEvent
    data class Redeem(val blockId: String)                  : BlockEvent
    data class UserResetEarned(val blockId: String)         : BlockEvent
    data class TimerControl(val blockId: String, val running: Boolean)     : BlockEvent  // nút "Bắt đầu"
    data class StartLockdown(val blockId: String)           : BlockEvent   // I21
    data class StartPause(val durationMs: Long = 15 * 60 * 1000) : BlockEvent  // I25
    data object EndPause                                     : BlockEvent   // I25
    data class SharedStateMerged(val base: SharedState?, val remote: SharedState) : BlockEvent  // §2.4, I26/I27
    data class BeginOverride(val blockIds: Set<String>)     : BlockEvent   // I34
    data object CancelOverride                               : BlockEvent   // I34
    data class ClaimOverride(val phrase: String)            : BlockEvent   // I34
    data class LogHabit(val habitId: String, val amount: Long) : BlockEvent   // I35
    data class SetHabitEntry(val habitId: String, val epochDay: Long, val value: Long, val notes: String) : BlockEvent  // I35
    data class HabitServerPulled(val habits: List<HabitRecord>, val entries: List<EntryRecord>, val seq: Long, val dayStartHour: Int?) : BlockEvent  // I38
    data class HabitServerPushed(val sent: HabitPush, val accepted: HabitAcceptance, val rejectedHabits: List<HabitRecord>,
                                 val rejectedEntries: List<EntryRecord>) : BlockEvent  // I38
    data class StartFocus(val habitId: String)              : BlockEvent   // I37
    data class PauseFocus(val breakMs: Long = 0)            : BlockEvent   // I37
    data object EndFocus                                     : BlockEvent   // I37
    data object BootOrColdStart                              : BlockEvent
}
```

- `ForegroundChanged`: trước hết cộng giờ cho foreground *cũ* (timer HABIT theo I4/I16/I18,
  hạn mức ALLOWANCE theo I19), rồi mới đổi sang `pkg`/`site` mới. `site` chỉ khác null khi
  `pkg` là trình duyệt và lớp Android đọc được host; màn hình tắt ⇒ lớp Android gửi
  `ForegroundChanged(null)` để không cộng giờ lúc máy khóa.
- `Tick`: kiểm I1 (credit hết hạn), I3 (tamper), UNLOCKED timeUp, phát cảnh báo 30s.
- `ToggleChecklistItem`: chỉ hiệu lực khi block ARMED (I12) và item không phải habit item (I23);
  sau khi toggle, kiểm `triggerMet`.
- `SharedStateMerged`: lớp ngoài (điện thoại: `SharedSync`; laptop: daemon) gửi sau khi đọc server;
  engine trộn và nhận ngay trong `reduce`, dưới mutex của store, nên không chạy đua với sự kiện
  khác. Xem §2.4.
- `LogHabit` / `SetHabitEntry` / `StartFocus` / `PauseFocus` / `EndFocus`: ghi habit (I35) và đồng
  hồ tập trung (I37); habit item của mọi block `ARMED` được tính lại sau mọi sự kiện (I36), nên ghi
  ở máy kia (qua `HabitServerPulled`) cũng làm block ở đây `EARNED`.
- `HabitServerPulled` / `HabitServerPushed`: một vòng đồng bộ với habit server (§2.5, I38). Đọc
  thất bại thì lớp ngoài **không** gửi gì; engine không bao giờ coi "không đọc được" là "chưa làm".
- `BootOrColdStart`: `rebuild()` — xem I8.

---

## 6. Những gì KHÔNG thuộc hợp đồng này

- Chiến lược phát hiện foreground (UsageStats vs Accessibility) — chi tiết Android, xem brainstorm §4.1.
- Cách biết trình duyệt đang mở site nào (extension của xrnrule trong trình duyệt, báo host cho
  app/daemon qua `127.0.0.1`) và thống kê thời gian theo website — chi tiết lớp ngoài; engine chỉ
  nhận `site` qua `ForegroundChanged`.
- Habit server (`xrnloops-sync`), giao thức HTTP của nó và lúc nào đồng bộ; nhắc nhở, widget, biểu
  đồ — chi tiết lớp ngoài; engine chỉ nhận `HabitServerPulled`/`HabitServerPushed` (§2.5). Luật
  điểm số, chuỗi ngày và tự điền của Loop nằm ở `LoopRules` trong `:domain` (port từ Loop, test bằng
  số liệu test của Loop), dùng chung cho engine (I23) và biểu đồ.
- Server đồng bộ (`server/xrnrule-sync.py`), giao thức HTTP, lúc nào đồng bộ và cách laptop phát
  hiện app/website — chi tiết lớp ngoài; engine chỉ nhận `SharedStateMerged` (§2.4).
- Giao diện overlay, layout 3 màn hình — xem brainstorm §5.
- Cơ chế sống sót (foreground service, WorkManager, OEM autostart) — brainstorm §4.4.
- Device Admin / anti-bypass — module `:hardcore`, brainstorm §4.5.
- Health Connect / Location / Beacon — phase E.

---

## 7. Ghi chú test

- `:domain` dùng `FakeClock`, `InMemoryPersist`, `RecordingShield` — không JUnit-Android, không Robolectric.
- Mỗi bất biến I1–I40 có ít nhất một test đặt tên `i01_...` ... `i40_...`; quy tắc sửa block (§2.3) nằm ở `BlockEditsTest`, bảng trộn (§2.4) ở `SharedStateTest`.
- Acceptance của brainstorm §1 map thành test tích hợp cấp `:domain` trong `AcceptanceTests.kt`,
  chạy chuỗi sự kiện end-to-end trên engine.
