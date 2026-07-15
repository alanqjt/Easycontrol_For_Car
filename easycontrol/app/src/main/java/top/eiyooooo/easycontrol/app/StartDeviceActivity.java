package top.eiyooooo.easycontrol.app;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.widget.Toast;
import top.eiyooooo.easycontrol.app.client.Client;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.DeviceListAdapter;
/**
 * 类 StartDeviceActivity
 * 说明：该类负责 StartDeviceActivity 相关功能。
 */

public class StartDeviceActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppData.init(this);
        startDevice();
        finish();
    }

    private void startDevice() {
        String uuid = getIntent().getStringExtra("uuid");

        if (uuid != null) {
            Device device = AppData.dbHelper.getByUUID(uuid);
            if (device == null) {
                Toast.makeText(this, getString(R.string.error_device_not_found), Toast.LENGTH_SHORT).show();
                return;
            }
            int mode = AppData.setting.getTryStartDefaultInAppTransfer()
                    || (device.specified_app != null && !device.specified_app.isEmpty()) ? 1 : 0;
            if (AppData.setting.getEmbeddedProjectionMode()) {
                launchEmbeddedMain(uuid, mode, false);
                DeviceListAdapter.startedDefault = true;
                return;
            }
            UsbDevice usbDevice = null;
            if (device.isLinkDevice()) {
                if (DeviceListAdapter.linkDevices.containsKey(device.uuid)) {
                    usbDevice = DeviceListAdapter.linkDevices.get(device.uuid);
                } else {
                    Toast.makeText(this, getString(R.string.error_device_not_found), Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            Client.start(device, usbDevice, mode);
        } else {
            if (AppData.setting.getEmbeddedProjectionMode()) {
                boolean found = false;
                for (Device device : AppData.dbHelper.getAll()) {
                    if (device.connectOnStart) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    Toast.makeText(this, getString(R.string.error_default_device_not_found), Toast.LENGTH_SHORT).show();
                    return;
                }
                launchEmbeddedMain(null, AppData.setting.getTryStartDefaultInAppTransfer() ? 1 : 0, true);
                DeviceListAdapter.startedDefault = true;
                return;
            }
            boolean found = false;
            for (Device device : AppData.dbHelper.getAll()) {
                UsbDevice usbDevice = null;
                if (!device.connectOnStart) continue;
                if (device.isLinkDevice()) {
                    if (DeviceListAdapter.linkDevices.containsKey(device.uuid)) {
                        usbDevice = DeviceListAdapter.linkDevices.get(device.uuid);
                    } else continue;
                }
                found = true;
                Client.start(device, usbDevice, AppData.setting.getTryStartDefaultInAppTransfer() ? 1 : 0);
            }
            if (!found) {
                Toast.makeText(this, getString(R.string.error_default_device_not_found), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        DeviceListAdapter.startedDefault = true;
    }

    private void launchEmbeddedMain(String uuid, int mode, boolean startDefault) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_EMBEDDED_START_MODE, mode);
        intent.putExtra(MainActivity.EXTRA_EMBEDDED_START_DEFAULT, startDefault);
        if (uuid != null) intent.putExtra(MainActivity.EXTRA_EMBEDDED_START_UUID, uuid);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
