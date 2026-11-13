package be.lampes.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bluetooth basse consommation pour la page (appelé depuis le JavaScript sous le nom AndroidBle).
 * Différence clé avec le navigateur du PC : on demande un MTU de 517, ce qui permet d'envoyer
 * les paquets d'image de 241 octets du rideau en un seul morceau.
 * Android n'accepte qu'une opération GATT à la fois par appareil : tout passe par une file.
 * Les réponses repartent vers la page via window.__ble.*.
 */
@SuppressLint("MissingPermission")
public class BleBridge {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private interface Action { boolean run() throws Exception; }

    private static class Op {
        String id;
        Action action;
    }

    private static class Conn {
        String addr;
        BluetoothGatt gatt;
        int mtu = 23;
        final ArrayDeque<Op> queue = new ArrayDeque<>();
        Op current;
        Runnable timeout;
    }

    private final Activity act;
    private final WebView web;
    private final BluetoothAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Conn> conns = new HashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;

    BleBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        BluetoothManager m = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = m != null ? m.getAdapter() : null;
    }

    // ---------- vers la page ----------
    private void js(final String code) {
        main.post(() -> web.evaluateJavascript("window.__ble && " + code, null));
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ---------- état ----------
    @JavascriptInterface
    public boolean enabled() {
        return adapter != null && adapter.isEnabled();
    }

    @JavascriptInterface
    public void enable() {
        main.post(() -> {
            try { act.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { }
        });
    }

    // ---------- recherche ----------
    @JavascriptInterface
    public void startScan() {
        main.post(() -> {
            try {
                stopScanNow();
                if (adapter == null || !adapter.isEnabled()) { js("__ble.onScanError('Bluetooth is off')"); return; }
                scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) { js("__ble.onScanError('Bluetooth unavailable')"); return; }
                scanCb = new ScanCallback() {
                    @Override public void onScanResult(int type, ScanResult r) { report(r); }
                    @Override public void onBatchScanResults(List<ScanResult> list) { for (ScanResult r : list) report(r); }
                    @Override public void onScanFailed(int code) { js("__ble.onScanError(" + q("erreur " + code) + ")"); }
                };
                ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                scanner.startScan(null, st, scanCb);
            } catch (Exception e) {
                js("__ble.onScanError(" + q(String.valueOf(e.getMessage())) + ")");
            }
        });
    }

    private void report(ScanResult r) {
        String name = null;
        JSONArray uuids = new JSONArray();
        ScanRecord rec = r.getScanRecord();
        if (rec != null) {
            name = rec.getDeviceName();
            if (rec.getServiceUuids() != null) for (ParcelUuid p : rec.getServiceUuids()) uuids.put(p.toString());
        }
        if (name == null) {
            try { name = r.getDevice().getName(); } catch (Exception ignored) { }
        }
        js("__ble.onScan(" + q(r.getDevice().getAddress()) + "," + q(name) + "," + r.getRssi() + "," + q(uuids.toString()) + ")");
    }

    @JavascriptInterface
    public void stopScan() { main.post(this::stopScanNow); }

    private void stopScanNow() {
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) { }
        scanCb = null;
    }

    // ---------- connexion ----------
    @JavascriptInterface
    public void connect(final String addr) {
        main.post(() -> {
            try {
                Conn old = conns.remove(addr);
                if (old != null && old.gatt != null) { try { old.gatt.close(); } catch (Exception ignored) { } }
                Conn c = new Conn();
                c.addr = addr;
                conns.put(addr, c);
                BluetoothDevice dev = adapter.getRemoteDevice(addr);
                c.gatt = dev.connectGatt(act, false, new Callback(c), BluetoothDevice.TRANSPORT_LE);
            } catch (Exception e) {
                js("__ble.onDisconnected(" + q(addr) + "," + q(String.valueOf(e.getMessage())) + ")");
            }
        });
    }

    @JavascriptInterface
    public void disconnect(final String addr) {
        main.post(() -> {
            Conn c = conns.remove(addr);
            if (c != null) {
                failAll(c);
                if (c.gatt != null) { try { c.gatt.disconnect(); c.gatt.close(); } catch (Exception ignored) { } }
            }
            js("__ble.onDisconnected(" + q(addr) + ",'')");
        });
    }

    void closeAll() {
        stopScanNow();
        for (Conn c : conns.values()) {
            if (c.gatt != null) { try { c.gatt.disconnect(); c.gatt.close(); } catch (Exception ignored) { } }
        }
        conns.clear();
    }

    private class Callback extends BluetoothGattCallback {
        private final Conn c;
        Callback(Conn c) { this.c = c; }

        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                main.post(() -> {
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        // gros paquets : c'est ce qui manque au navigateur du PC
                        if (!g.requestMtu(517)) g.discoverServices();
                    } catch (Exception e) {
                        try { g.discoverServices(); } catch (Exception ignored) { }
                    }
                });
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                main.post(() -> {
                    failAll(c);
                    try { g.close(); } catch (Exception ignored) { }
                    if (conns.get(c.addr) == c) conns.remove(c.addr);
                    js("__ble.onDisconnected(" + q(c.addr) + "," + q("status " + status) + ")");
                });
            }
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) c.mtu = mtu;
            main.post(() -> { try { g.discoverServices(); } catch (Exception ignored) { } });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            JSONArray arr = new JSONArray();
            try {
                for (BluetoothGattService s : g.getServices()) {
                    JSONObject o = new JSONObject();
                    o.put("uuid", s.getUuid().toString());
                    JSONArray chars = new JSONArray();
                    for (BluetoothGattCharacteristic k : s.getCharacteristics()) {
                        JSONObject x = new JSONObject();
                        x.put("uuid", k.getUuid().toString());
                        x.put("props", k.getProperties());
                        chars.put(x);
                    }
                    o.put("chars", chars);
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            js("__ble.onConnected(" + q(c.addr) + "," + c.mtu + "," + q(arr.toString()) + ")");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic k, int status) {
            done(c, status == BluetoothGatt.GATT_SUCCESS, null);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            done(c, status == BluetoothGatt.GATT_SUCCESS, null);
        }

        // Android 12 et avant
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic k, int status) {
            done(c, status == BluetoothGatt.GATT_SUCCESS, k.getValue());
        }

        // Android 13+
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic k, byte[] value, int status) {
            done(c, status == BluetoothGatt.GATT_SUCCESS, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic k) {
            notifyPage(c, k, k.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic k, byte[] value) {
            notifyPage(c, k, value);
        }
    }

    private void notifyPage(Conn c, BluetoothGattCharacteristic k, byte[] v) {
        if (v == null) return;
        js("__ble.onNotify(" + q(c.addr) + "," + q(k.getUuid().toString()) + "," + q(Base64.encodeToString(v, Base64.NO_WRAP)) + ")");
    }

    // ---------- file d'opérations ----------
    private BluetoothGattCharacteristic find(Conn c, String svc, String chr) {
        if (c.gatt == null) return null;
        BluetoothGattService s = c.gatt.getService(UUID.fromString(svc));
        return s == null ? null : s.getCharacteristic(UUID.fromString(chr));
    }

    private void enqueue(final String addr, final String id, final ActionFactory f) {
        main.post(() -> {
            Conn c = conns.get(addr);
            if (c == null || c.gatt == null) {
                js("__ble.onOp(" + q(id) + ",false,'')");
                return;
            }
            Op o = new Op();
            o.id = id;
            o.action = f.make(c);
            c.queue.add(o);
            if (c.current == null) next(c);
        });
    }

    private interface ActionFactory { Action make(Conn c); }

    private void next(final Conn c) {
        c.current = c.queue.poll();
        if (c.current == null) return;
        final Op o = c.current;
        c.timeout = () -> { if (c.current == o) done(c, false, null); };
        main.postDelayed(c.timeout, 4000);
        boolean started;
        try { started = o.action.run(); } catch (Exception e) { started = false; }
        if (!started) done(c, false, null);
    }

    private void done(final Conn c, final boolean ok, final byte[] value) {
        main.post(() -> {
            Op o = c.current;
            if (o == null) return;
            if (c.timeout != null) main.removeCallbacks(c.timeout);
            c.current = null;
            String v = value == null ? "" : Base64.encodeToString(value, Base64.NO_WRAP);
            js("__ble.onOp(" + q(o.id) + "," + ok + "," + q(v) + ")");
            next(c);
        });
    }

    private void failAll(Conn c) {
        if (c.current != null) js("__ble.onOp(" + q(c.current.id) + ",false,'')");
        for (Op o : c.queue) js("__ble.onOp(" + q(o.id) + ",false,'')");
        c.queue.clear();
        c.current = null;
        if (c.timeout != null) main.removeCallbacks(c.timeout);
    }

    // ---------- opérations appelées par la page ----------
    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public void write(String addr, final String svc, final String chr, String b64, final boolean withResponse, String id) {
        final byte[] data = Base64.decode(b64, Base64.NO_WRAP);
        enqueue(addr, id, c -> () -> {
            BluetoothGattCharacteristic k = find(c, svc, chr);
            if (k == null) return false;
            int type = withResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            if (Build.VERSION.SDK_INT >= 33) {
                return c.gatt.writeCharacteristic(k, data, type) == BluetoothStatusCodes.SUCCESS;
            }
            k.setWriteType(type);
            k.setValue(data);
            return c.gatt.writeCharacteristic(k);
        });
    }

    @JavascriptInterface
    public void read(String addr, final String svc, final String chr, String id) {
        enqueue(addr, id, c -> () -> {
            BluetoothGattCharacteristic k = find(c, svc, chr);
            return k != null && c.gatt.readCharacteristic(k);
        });
    }

    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public void notify(String addr, final String svc, final String chr, String id) {
        enqueue(addr, id, c -> () -> {
            BluetoothGattCharacteristic k = find(c, svc, chr);
            if (k == null || !c.gatt.setCharacteristicNotification(k, true)) return false;
            BluetoothGattDescriptor d = k.getDescriptor(CCCD);
            if (d == null) { done(c, true, null); return true; }
            boolean indicateOnly = (k.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (k.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            byte[] v = indicateOnly ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) {
                return c.gatt.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS;
            }
            d.setValue(v);
            return c.gatt.writeDescriptor(d);
        });
    }
}
