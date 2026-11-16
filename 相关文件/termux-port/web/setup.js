(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var ST = { status: null, step: 1, from: "first", busy: false, scan: null, error: "" };

  function el(tag, cls, text) {
    var d = document.createElement(tag);
    if (cls) d.className = cls;
    if (text !== undefined && text !== null) d.textContent = text;
    return d;
  }

  function api(path, opts) {
    var options = { cache: "no-store" };
    if (opts) {
      for (var k in opts) {
        if (Object.prototype.hasOwnProperty.call(opts, k)) options[k] = opts[k];
      }
    }
    return fetch(path, options).then(function (res) {
      return res.json().catch(function () {
        return { ok: false, error: "服务返回了非 JSON" };
      }).then(function (data) {
        if (!res.ok && !data.error) data.error = "HTTP " + res.status;
        return data;
      });
    });
  }

  function step(id) {
    if (!ST.status || !ST.status.steps) return null;
    for (var i = 0; i < ST.status.steps.length; i++) {
      if (ST.status.steps[i].id === id) return ST.status.steps[i];
    }
    return null;
  }

  function stepOk(n) {
    if (n === 1) return !!(step("phone_ip") && step("phone_ip").ok);
    if (n === 2) return !!(step("tv_adb") && step("tv_adb").ok);
    if (n === 3) return !!(step("mpv") && step("mpv").ok);
    if (n === 4) return !!(step("proxy") && step("proxy").ok &&
                          step("content") && step("content").ok);
    return stepOk(1) && stepOk(2) && stepOk(3) && stepOk(4);
  }

  function firstFail() {
    if (!stepOk(1)) return 1;
    if (!stepOk(2)) return 2;
    if (!stepOk(3)) return 3;
    if (!stepOk(4)) return 4;
    return 5;
  }

  function isFirstRun(s) {
    var c = (s && s.config) || {};
    var rooms = c.rooms || [];
    return !c.tv && !c.public_host && !(rooms && rooms.length);
  }

  function showOverlay() {
    $("setupOverlay").classList.remove("hidden");
  }

  function hideOverlay() {
    $("setupOverlay").classList.add("hidden");
  }

  function updateBanner() {
    var banner = $("healthBanner");
    if (!ST.status || ST.status.complete) {
      banner.classList.add("hidden");
      return;
    }
    var n = firstFail();
    var s = n === 1 ? step("phone_ip") : n === 2 ? step("tv_adb") :
            n === 3 ? step("mpv") : n === 4 ? (step("proxy") || step("content")) : null;
    $("healthBannerText").textContent = "配置未完成：" +
      (s && s.label ? s.label : "第 " + n + " 步") +
      (s && s.detail ? " · " + s.detail : "");
    banner.classList.remove("hidden");
  }

  function updateSettingsLabels() {
    if (!ST.status) return;
    var p = ST.status.phone || {};
    var t = ST.status.tv || {};
    var c = ST.status.content || {};
    var phone = $("editPhoneVal");
    var tv = $("editTvVal");
    var mpv = $("editMpvVal");
    var content = $("editContentVal");
    if (phone) phone.textContent = p.current || p.configured || "未配置";
    if (tv) tv.textContent = t.configured || "未配置";
    if (mpv) mpv.textContent = t.mpv ? "已安装" : "未安装";
    if (content) {
      if (c.source === "video") {
        content.textContent = "合集 " + (c.list_mid || "?") + "/" + (c.list_id || "?");
      } else {
        content.textContent = (c.rooms && c.rooms.length) ?
          ("直播间 " + c.rooms.join(",")) : "未选择";
      }
    }
  }

  function load(cb) {
    ST.busy = true;
    var next = $("setupNext");
    if (next) next.disabled = true;
    api("/api/setup/status").then(function (d) {
      ST.busy = false;
      if (!d || !d.ok) {
        ST.error = (d && d.error) || "读取配置状态失败";
      } else {
        ST.status = d;
        ST.error = "";
      }
      updateSettingsLabels();
      if (cb) cb();
    }).catch(function (e) {
      ST.busy = false;
      ST.error = "控制服务没响应：" + e.message;
      if (cb) cb();
    });
  }

  function render() {
    if ($("setupOverlay").classList.contains("hidden")) return;

    var title = ST.from === "first" ? "首次配置" : "配置检查";
    $("setupTitle").textContent = title;
    $("setupProgress").textContent = ST.step + "/5";

    var dots = $("setupDots");
    dots.innerHTML = "";
    for (var i = 1; i <= 5; i++) {
      var d = el("div", "setup-dot");
      if (i < ST.step) d.className += " done";
      if (i === ST.step) d.className += " active";
      if (i === ST.step && !stepOk(i)) d.className += " fail";
      dots.appendChild(d);
    }

    $("setupClose").classList.toggle("hidden", ST.from === "first");
    $("setupBack").disabled = ST.step <= 1 || ST.busy;
    $("setupRetry").disabled = ST.busy;
    $("setupNext").disabled = ST.busy || !stepOk(ST.step);
    $("setupNext").textContent = ST.step === 5 ? "开始使用" : "下一步";

    var body = $("setupBody");
    body.innerHTML = "";

    if (ST.error) {
      body.appendChild(el("div", "setup-error", ST.error));
      if (!ST.status) {
        var retry = el("div", "setup-actions");
        var b = el("button", "", "重新检查");
        b.onclick = function () { ST.error = ""; load(render); };
        retry.appendChild(b);
        body.appendChild(retry);
        return;
      }
    }

    if (!ST.status) {
      body.appendChild(el("div", "setup-loading", "正在检查…"));
      return;
    }

    if (ST.step === 1) renderPhone(body);
    else if (ST.step === 2) renderTv(body);
    else if (ST.step === 3) renderMpv(body);
    else if (ST.step === 4) renderContent(body);
    else renderDone(body);
  }

  function addKV(parent, k, v) {
    var row = el("div", "setup-kv");
    row.appendChild(el("span", "", k));
    row.appendChild(el("span", "", v || "—"));
    parent.appendChild(row);
  }

  function addActions(parent) {
    var box = el("div", "setup-actions");
    parent.appendChild(box);
    return box;
  }

  function renderPhone(body) {
    var p = ST.status.phone || {};
    body.appendChild(el("div", "setup-step-title", "1. 手机网络"));
    body.appendChild(el("div", "setup-step-desc",
      "电视要能访问这台手机，所以手机的 WiFi IP 必须填对。\n" +
      "换 WiFi 后，这里会自动检测到新地址。"));
    addKV(body, "当前手机 IP", p.current || "没读到");
    addKV(body, "配置里的 IP", p.configured || "未配置");

    if (stepOk(1)) {
      body.appendChild(el("div", "setup-ok", "手机网络已正确，可以下一步。"));
    } else if (p.current) {
      body.appendChild(el("div", "setup-bad",
        p.configured ? "当前 IP 和配置不一致。点下面按钮用当前地址重新保存。"
                     : "配置里还没有手机 IP。点下面按钮保存当前地址。"));
      var actions = addActions(body);
      var use = el("button", "blue", "使用当前 IP：" + p.current);
      use.onclick = function () { applyPhone(p.current); };
      actions.appendChild(use);
    } else {
      body.appendChild(el("div", "setup-bad",
        "没读到手机 WiFi IP。先确认手机连着 WiFi，再点“重试”。"));
    }

    body.appendChild(el("div", "setup-note",
      "说明：这里用的是手机在 WiFi 里的 IPv4，不是手机型号/序列号。\n" +
      "如果自动检测到的是蜂窝网地址，请在 Termux 执行 ip -4 addr show wlan0 手动确认。"));
  }

  function renderTv(body) {
    var t = ST.status.tv || {};
    body.appendChild(el("div", "setup-step-title", "2. 电视连接"));
    body.appendChild(el("div", "setup-step-desc",
      "电视要打开“网络调试/ADB over network”，并允许这台手机调试。\n" +
      "换 WiFi 后电视 IP 也会变，可以扫描当前网段。"));
    addKV(body, "当前电视地址", t.configured || "未配置");
    addKV(body, "ADB 状态", t.state || "未连接");

    if (stepOk(2)) {
      body.appendChild(el("div", "setup-ok", "电视 ADB 已连接。下面可以重新扫描/切换到另一台电视。"));
    } else {
      body.appendChild(el("div", "setup-bad",
        (step("tv_adb") && step("tv_adb").detail) || "电视还没连上。"));
    }

    var scanBox = addActions(body);
    var scanBtn = el("button", "blue", "扫描局域网找电视");
    scanBtn.onclick = function () { scanTv(scanBtn); };
    scanBox.appendChild(scanBtn);

    var manual = el("div", "setup-input-row");
    manual.style.marginTop = "10px";
    var input = document.createElement("input");
    input.id = "setupTvInput";
    input.placeholder = "手动输入：192.168.1.23:5555";
    input.autocomplete = "off";
    var connect = el("button", "", "连接并检测");
    connect.onclick = function () { applyTv(input.value); };
    manual.appendChild(input);
    manual.appendChild(connect);
    body.appendChild(manual);

    if (ST.scan) {
      if (ST.scan.busy) {
        body.appendChild(el("div", "setup-loading", "扫描中…"));
      } else if (ST.scan.error) {
        body.appendChild(el("div", "setup-error", ST.scan.error));
      } else if (ST.scan.candidates && ST.scan.candidates.length) {
        var list = el("div", "");
        list.style.marginTop = "8px";
        ST.scan.candidates.forEach(function (c) {
          var row = el("div", "setup-scan-item");
          var grow = el("div", "grow");
          grow.appendChild(el("div", "ip", c.ip));
          grow.appendChild(el("div", "meta",
            (c.model || "Android 设备") + " · " + (c.state || "未知状态")));
          row.appendChild(grow);
          var b = el("button", "", "使用");
          b.onclick = function () { applyTv(c.tv); };
          row.appendChild(b);
          list.appendChild(row);
        });
        body.appendChild(list);
      } else {
        body.appendChild(el("div", "setup-note",
          (ST.scan.hint || "没扫到电视。") +
          "\n请确认：电视开机、网络调试打开、和手机同一个 WiFi、路由器没开 AP 隔离。"));
      }
    }

    body.appendChild(el("div", "setup-note",
      "第一次连接时电视会弹“允许 USB 调试吗”，勾选“一律允许”再点允许。\n" +
      "如果显示 unauthorized，点“重试”前先去电视上点允许。"));
  }

  function renderMpv(body) {
    var m = step("mpv");
    body.appendChild(el("div", "setup-step-title", "3. 电视播放器"));
    body.appendChild(el("div", "setup-step-desc",
      "电视上必须装 mpv-android（包名 is.xyz.mpv）。\n" +
      "没有它，派台成功也是黑屏。"));
    if (m && m.ok) {
      body.appendChild(el("div", "setup-ok", "电视上已装 mpv-android。"));
    } else {
      body.appendChild(el("div", "setup-bad",
        (m && m.detail) || "电视上还没找到 mpv-android。"));
    }
    body.appendChild(el("div", "setup-note",
      "安装方法（三选一）：\n" +
      "1. 电视应用商店/当贝市场搜 “mpv”\n" +
      "2. 电视浏览器打开 f-droid.org，搜 mpv-android\n" +
      "3. 电脑下载 APK，adb connect 后 adb install -r mpv-android.apk"));
    var actions = addActions(body);
    var b = el("button", "blue", "重新检测");
    b.onclick = function () { load(render); };
    actions.appendChild(b);
  }

  function renderContent(body) {
    var proxy = step("proxy");
    var content = step("content");
    body.appendChild(el("div", "setup-step-title", "4. 选一个直播间"));
    body.appendChild(el("div", "setup-step-desc",
      "搜 UP 名字，或直接粘贴直播间链接。\n" +
      "这一版是单直播间模式：只播这一个，不自动轮换。"));

    if (!proxy || !proxy.ok) {
      body.appendChild(el("div", "setup-bad",
        (proxy && proxy.detail) || "8099 代理没响应。"));
      var a = addActions(body);
      var start = el("button", "blue", "启动 tvstation");
      start.onclick = function () { startService(); };
      a.appendChild(start);
      return;
    }

    addKV(body, "代理服务", "8099 正常");
    if (content && content.ok) {
      body.appendChild(el("div", "setup-ok", "当前内容：" + content.detail));
    } else {
      body.appendChild(el("div", "setup-note", "还没有选直播间。"));
    }

    var row = el("div", "setup-input-row");
    row.style.marginTop = "10px";
    var input = document.createElement("input");
    input.id = "setupSearchInput";
    input.placeholder = "输入 UP 名字，或粘贴直播间链接";
    input.autocomplete = "off";
    var btn = el("button", "", "搜索 / 播放");
    btn.onclick = function () { setupSearch(btn); };
    row.appendChild(input);
    row.appendChild(btn);
    body.appendChild(row);

    var hint = el("div", "setup-note",
      "支持：UP 名字（如 老番茄）；直播间链接（https://live.bilibili.com/5017134）；纯数字房间号。\n" +
      "合集/单个视频是第二版。");
    body.appendChild(hint);

    var err = el("div", "setup-error hidden");
    err.id = "setupSearchError";
    body.appendChild(err);

    var results = el("div", "");
    results.id = "setupResults";
    body.appendChild(results);

    if (content && content.ok && (content.detail || "").indexOf("直播中") >= 0) {
      var act = addActions(body);
      var test = el("button", "green", "测试电视画面");
      test.onclick = function () { testPlayback(test); };
      act.appendChild(test);
      var testMsg = el("div", "setup-note hidden");
      testMsg.id = "setupTestMsg";
      body.appendChild(testMsg);
    }
  }

  function renderDone(body) {
    var p = ST.status.phone || {};
    var t = ST.status.tv || {};
    var c = ST.status.content || {};
    body.appendChild(el("div", "setup-step-title", "5. 完成"));
    body.appendChild(el("div", "setup-step-desc",
      "配置已保存。以后打开 App 会自动检查；哪一项不对，就只弹那一项。"));
    addKV(body, "手机 IP", p.current || p.configured || "—");
    addKV(body, "电视", t.configured || "—");
    addKV(body, "播放器", t.mpv ? "已安装" : "未安装");
    if (c.source === "video") {
      addKV(body, "内容", "合集 " + (c.list_mid || "?") + "/" + (c.list_id || "?"));
    } else {
      addKV(body, "直播间", (c.rooms && c.rooms.length) ? c.rooms.join(",") : "—");
    }
    addKV(body, "代理", "8099");
    body.appendChild(el("div", "setup-note",
      "点“开始使用”进入控制台。\n" +
      "换 WiFi / 换电视后，在设置里点“重新初始化”，或打开 App 时按提示修。"));
  }

  function applyPhone(host) {
    if (ST.busy) return;
    ST.busy = true;
    ST.error = "";
    render();
    api("/api/setup/apply_phone", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ public_host: host })
    }).then(function (d) {
      ST.busy = false;
      if (!d.ok) {
        ST.error = d.error || "保存失败";
        render();
        return;
      }
      load(render);
    }).catch(function (e) {
      ST.busy = false;
      ST.error = "请求失败：" + e.message;
      render();
    });
  }

  function applyTv(tv) {
    if (ST.busy) return;
    if (!tv) {
      ST.error = "先输入电视 IP，例如 192.168.1.23:5555。";
      render();
      return;
    }
    ST.busy = true;
    ST.error = "";
    render();
    api("/api/setup/apply_tv", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tv: tv })
    }).then(function (d) {
      ST.busy = false;
      if (!d.ok) {
        ST.error = (d.error || "连接失败") +
          (d.hint ? "\n" + d.hint : "");
        render();
        return;
      }
      load(render);
    }).catch(function (e) {
      ST.busy = false;
      ST.error = "请求失败：" + e.message;
      render();
    });
  }

  function scanTv(btn) {
    if (ST.busy) return;
    ST.scan = { busy: true };
    ST.error = "";
    btn.disabled = true;
    render();
    api("/api/setup/scan_tv").then(function (d) {
      ST.scan = d.ok ? d : { error: d.error || "扫描失败" };
      render();
    }).catch(function (e) {
      ST.scan = { error: "扫描失败：" + e.message };
      render();
    });
  }

  function startService() {
    if (ST.busy) return;
    ST.busy = true;
    ST.error = "";
    render();
    api("/api/control", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: "start" })
    }).then(function (d) {
      ST.busy = false;
      if (!d.ok) {
        ST.error = d.error || "启动失败";
        render();
        return;
      }
      load(render);
    }).catch(function (e) {
      ST.busy = false;
      ST.error = "请求失败：" + e.message;
      render();
    });
  }

  function setupSearch(btn) {
    var input = $("setupSearchInput");
    var q = input ? input.value.trim() : "";
    var err = $("setupSearchError");
    var box = $("setupResults");
    if (!q) {
      if (err) { err.textContent = "先输入 UP 名字或粘贴直播间链接。"; err.classList.remove("hidden"); }
      return;
    }
    btn.disabled = true;
    if (err) err.classList.add("hidden");
    if (box) box.innerHTML = "";
    api("/api/search?q=" + encodeURIComponent(q)).then(function (d) {
      btn.disabled = false;
      if (!d.ok) {
        if (err) { err.textContent = d.error || "搜索失败"; err.classList.remove("hidden"); }
        return;
      }
      if (d.kind === "live" && d.room) {
        renderSetupLive(box, d.room);
      } else if (d.kind === "candidates") {
        renderSetupCandidates(box, d.candidates || []);
      }
    }).catch(function (e) {
      btn.disabled = false;
      if (err) { err.textContent = "请求失败：" + e.message; err.classList.remove("hidden"); }
    });
  }

  function renderSetupLive(box, room) {
    var card = el("div", "livecard");
    card.appendChild(el("div", "label", "找到直播间 " + room.room_id));
    card.appendChild(el("div", "title", room.title || "(无标题)"));
    card.appendChild(el("div", "meta",
      (room.live ? "直播中" : "当前未开播") +
      (room.online ? " · 人气 " + room.online : "")));
    var b = el("button", "", "播放这个直播间");
    b.style.marginTop = "10px";
    b.onclick = function () { setupPlay(room.room_id); };
    card.appendChild(b);
    box.appendChild(card);
  }

  function renderSetupCandidates(box, items) {
    items.forEach(function (item) {
      var row = el("div", "result");
      var av = el("div", "avatar", (item.uname || "?").slice(0, 1));
      row.appendChild(av);
      var grow = el("div", "grow");
      grow.appendChild(el("div", "name", item.uname || ("mid " + item.mid)));
      grow.appendChild(el("div", "meta",
        "mid " + item.mid + " · " + (item.fans || 0).toLocaleString() +
        " 粉丝" + (item.room_id ? " · 房间 " + item.room_id : " · 无直播间")));
      row.appendChild(grow);
      if (item.room_id) {
        var b = el("button", "", "播放");
        b.onclick = function () { setupPlay(item.room_id); };
        row.appendChild(b);
      } else {
        row.appendChild(el("div", "no", "无直播间"));
      }
      box.appendChild(row);
    });
  }

  function setupPlay(roomId) {
    if (ST.busy) return;
    ST.busy = true;
    ST.error = "";
    render();
    api("/api/play_live", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ room_id: roomId })
    }).then(function (d) {
      ST.busy = false;
      if (!d.ok) {
        ST.error = d.error || "切换失败";
        render();
        return;
      }
      ST.error = "";
      load(function () {
        render();
        if (d.ok && d.message) {
          var body = $("setupBody");
          var m = el("div", "setup-ok", d.message);
          body.insertBefore(m, body.firstChild.nextSibling);
        }
      });
    }).catch(function (e) {
      ST.busy = false;
      ST.error = "请求失败：" + e.message;
      render();
    });
  }

  function testPlayback(btn) {
    btn.disabled = true;
    api("/api/setup/test_playback").then(function (d) {
      btn.disabled = false;
      var msg = $("setupTestMsg");
      if (!msg) return;
      msg.classList.remove("hidden");
      if (!d.ok) {
        msg.className = "setup-error";
        msg.textContent = d.error || "测试失败";
      } else {
        msg.className = d.playing ? "setup-ok" : "setup-note";
        msg.textContent = d.detail + " · media state=" + d.media_state +
          (d.playing ? "（播放中）" : "（可能还在缓冲/未开播）");
      }
    }).catch(function (e) {
      btn.disabled = false;
      var msg = $("setupTestMsg");
      if (msg) { msg.className = "setup-error"; msg.textContent = "请求失败：" + e.message; msg.classList.remove("hidden"); }
    });
  }

  function open(stepNo, from) {
    ST.step = stepNo || 1;
    ST.from = from || "settings";
    ST.error = "";
    ST.scan = null;
    showOverlay();
    render();
    load(render);
  }

  function close() {
    hideOverlay();
    updateBanner();
  }

  function finish() {
    hideOverlay();
    api("/api/setup/seen", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    }).then(function () {
      window.location.reload();
    }, function () {
      window.location.reload();
    });
  }

  $("setupBack").onclick = function () {
    if (ST.step > 1) {
      ST.step--;
      ST.error = "";
      render();
    }
  };
  $("setupRetry").onclick = function () { ST.error = ""; load(render); };
  $("setupNext").onclick = function () {
    if (!stepOk(ST.step)) return;
    if (ST.step < 5) {
      ST.step++;
      ST.error = "";
      render();
      $("setupBody").scrollTop = 0;
    } else {
      finish();
    }
  };
  $("setupClose").onclick = close;
  $("healthBannerBtn").onclick = function () { open(firstFail(), "health"); };

  if ($("editPhone")) $("editPhone").onclick = function () { open(1, "settings"); };
  if ($("editTv")) $("editTv").onclick = function () { open(2, "settings"); };
  if ($("editMpv")) $("editMpv").onclick = function () { open(3, "settings"); };
  if ($("editContent")) $("editContent").onclick = function () { open(4, "settings"); };
  if ($("btnReinit")) $("btnReinit").onclick = function () {
    if (!window.confirm("重新初始化会重新检查手机 IP、电视、播放器和内容。旧配置会保留到每一步保存前。继续？")) return;
    open(1, "settings");
  };

  window.TVSetup = {
    open: open,
    check: function () { load(function () { updateBanner(); render(); }); },
    close: close
  };

  // 页面加载：先查一次；完整就直接用，不完整就打开失败的那一步
  load(function () {
    updateSettingsLabels();
    if (!ST.status) {
      // 控制服务没响应：仍然显示一个可重试的卡片
      ST.from = "health";
      showOverlay();
      render();
      updateBanner();
      return;
    }
    if (!ST.status.complete) {
      ST.step = firstFail();
      ST.from = isFirstRun(ST.status) ? "first" : "health";
      showOverlay();
      render();
      updateBanner();
    } else if (!ST.status.seen) {
      // 后端已经配好了，但还没在 App 里走过一次向导：让用户确认一遍
      ST.step = 1;
      ST.from = "first";
      showOverlay();
      render();
      updateBanner();
    } else {
      updateBanner();
    }
  });
})();
