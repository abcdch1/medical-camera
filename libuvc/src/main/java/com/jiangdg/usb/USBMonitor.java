/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.jiangdg.usb;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.SparseArray;

import androidx.annotation.RequiresApi;

import com.jiangdg.utils.BuildCheck;
import com.jiangdg.utils.HandlerThreadHandler;
import com.jiangdg.utils.XLogWrapper;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class USBMonitor {
	// Singleton instance
	private static USBMonitor sInstance;
	
	/**
	 * Get the singleton instance of USBMonitor
	 * @param context Application context
	 * @return USBMonitor instance
	 */
	public static synchronized USBMonitor getInstance(final Context context) {
		if (sInstance == null) {
			XLogWrapper.i(TAG, "Creating new USBMonitor singleton instance");
			sInstance = new USBMonitor(context);
		} else {
			XLogWrapper.i(TAG, "Returning existing USBMonitor singleton instance");
		}
		return sInstance;
	}
	
	/**
	 * Set the device connect listener for the singleton instance
	 * @param listener The listener to set
	 */
	public void setOnDeviceConnectListener(final OnDeviceConnectListener listener) {
		
		if (listener == null) {
			throw new IllegalArgumentException("OnDeviceConnectListener should not be null.");
		}
		XLogWrapper.i(TAG, "Setting device connect listener");
		mOnDeviceConnectListener = listener;
	}
	
	/**
	 * Get the singleton instance of USBMonitor
	 * @return the singleton instance, or null if not created yet
	 */
	public static USBMonitor getSingletonInstance() {
		return sInstance;
	}

	


	public static boolean DEBUG = true;	// TODO set false on production
	private static final String TAG = "USBMonitor";

	private static final String ACTION_USB_PERMISSION_BASE = "com.serenegiant.USB_PERMISSION.";
	private final String ACTION_USB_PERMISSION = ACTION_USB_PERMISSION_BASE + hashCode();

	public static final String ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";

	/**
	 * openしているUsbControlBlock
	 */
	private final ConcurrentHashMap<UsbDevice, UsbControlBlock> mCtrlBlocks = new ConcurrentHashMap<UsbDevice, UsbControlBlock>();
	private final SparseArray<WeakReference<UsbDevice>> mHasPermissions = new SparseArray<WeakReference<UsbDevice>>();

	private final WeakReference<Context> mWeakContext;
	private final UsbManager mUsbManager;
	private OnDeviceConnectListener mOnDeviceConnectListener;
	private PendingIntent mPermissionIntent = null;
	private List<DeviceFilter> mDeviceFilters = new ArrayList<DeviceFilter>();

	/**
	 * コールバックをワーカースレッドで呼び出すためのハンドラー
	 */
	private final Handler mAsyncHandler;
	private volatile boolean destroyed;

	/**
	 * BroadcastReceiver for USB permission and device attach/detach events
	 */
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (destroyed) {
				XLogWrapper.w(TAG, "onReceive: USBMonitor already destroyed, ignoring event");
				return;
			}

			final String action = intent.getAction();
			XLogWrapper.i(TAG, "onReceive: action=" + action);

			if (ACTION_USB_PERMISSION.equals(action)) {
				// Permission request result
				synchronized (USBMonitor.this) {
					final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					if (device != null) {
						final boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
						XLogWrapper.i(TAG, "onReceive: USB_PERMISSION for device=" + device.getDeviceName() + 
							", VendorId=" + device.getVendorId() + 
							", ProductId=" + device.getProductId() + 
							", granted=" + granted);

						// Update internal permission state
						updatePermission(device, granted);

						// Process connection or cancellation
						if (granted) {
							XLogWrapper.i(TAG, "onReceive: Permission granted, processing connection");
							processConnect(device);
						} else {
							XLogWrapper.w(TAG, "onReceive: Permission denied, processing cancellation");
							processCancel(device);
						}
					} else {
						XLogWrapper.e(TAG, "onReceive: USB_PERMISSION with null device");
					}
				}
			} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				// Device attached
				final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				if (device != null) {
					XLogWrapper.i(TAG, "onReceive: USB_DEVICE_ATTACHED for device=" + device.getDeviceName() + 
						", VendorId=" + device.getVendorId() + 
						", ProductId=" + device.getProductId());

					// Check if the device matches our filters
					if (hasPermission(device)) {
						XLogWrapper.i(TAG, "onReceive: Already have permission for attached device, processing connection");
						processConnect(device);
					} else {
						XLogWrapper.i(TAG, "onReceive: Processing device attachment");
						processAttach(device);
					}
				} else {
					XLogWrapper.e(TAG, "onReceive: USB_DEVICE_ATTACHED with null device");
				}
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				// Device detached
				final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				if (device != null) {
					XLogWrapper.i(TAG, "onReceive: USB_DEVICE_DETACHED for device=" + device.getDeviceName() + 
						", VendorId=" + device.getVendorId() + 
						", ProductId=" + device.getProductId());

					// Clean up device resources
					UsbControlBlock ctrlBlock = mCtrlBlocks.remove(device);
					if (ctrlBlock != null) {
						XLogWrapper.i(TAG, "onReceive: Closing control block for detached device");
						ctrlBlock.close();
					}

					// Remove from permission cache
					synchronized (mHasPermissions) {
						final int deviceKey = getDeviceKey(device, true);
						if (mHasPermissions.get(deviceKey) != null) {
							XLogWrapper.i(TAG, "onReceive: Removing detached device from permission cache");
							mHasPermissions.remove(deviceKey);
						}
					}

					// Process detachment
					XLogWrapper.i(TAG, "onReceive: Processing device detachment");
					processDettach(device);
				} else {
					XLogWrapper.e(TAG, "onReceive: USB_DEVICE_DETACHED with null device");
				}
			}
		}
	};

	/**
	 * USB機器の状態変更時のコールバックリスナー
	 */
	public interface OnDeviceConnectListener {
		/**
		 * called when device attached
		 * @param device
		 */
		public void onAttach(UsbDevice device);
		/**
		 * called when device dettach(after onDisconnect)
		 * @param device
		 */
		public void onDetach(UsbDevice device);
		/**
		 * called after device opend
		 * @param device
		 * @param ctrlBlock
		 * @param createNew
		 */
		public void onConnect(UsbDevice device, UsbControlBlock ctrlBlock, boolean createNew);
		/**
		 * called when USB device removed or its power off (this callback is called after device closing)
		 * @param device
		 * @param ctrlBlock
		 */
		public void onDisconnect(UsbDevice device, UsbControlBlock ctrlBlock);
		/**
		 * called when canceled or could not get permission from user
		 * @param device
		 */
		public void onCancel(UsbDevice device);
	}

	// Private constructor for singleton pattern
	private USBMonitor(final Context context) {
		if (DEBUG) XLogWrapper.v(TAG, "USBMonitor:Constructor");
		mWeakContext = new WeakReference<Context>(context.getApplicationContext()); // Use application context to prevent leaks
		mUsbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
		mAsyncHandler = HandlerThreadHandler.createHandler(TAG);
		destroyed = false;
		XLogWrapper.i(TAG, "USBMonitor:mUsbManager=" + mUsbManager);
	}
	


	/**
	 * Release all related resources,
	 * never reuse again
	 */
	public void destroy() {
		XLogWrapper.i(TAG, "destroy: Cleaning up USB monitor resources");
		unregister();
		if (!destroyed) {
			destroyed = true;
			// Close all monitored USB devices
			final Set<UsbDevice> keys = mCtrlBlocks.keySet();
			if (keys != null) {
				UsbControlBlock ctrlBlock;
				try {
					for (final UsbDevice key: keys) {
						ctrlBlock = mCtrlBlocks.remove(key);
						if (ctrlBlock != null) {
							ctrlBlock.close();
						}
					}
				} catch (final Exception e) {
					XLogWrapper.e(TAG, "destroy:", e);
				}
			}
			mCtrlBlocks.clear();
			try {
				mAsyncHandler.getLooper().quit();
			} catch (final Exception e) {
				XLogWrapper.e(TAG, "destroy:", e);
			}
			
			// Clear the listener
			mOnDeviceConnectListener = null;
		}
		
		// Clear the singleton instance if this is the singleton instance being destroyed
		if (sInstance == this) {
			XLogWrapper.i(TAG, "destroy: Clearing singleton instance");
			sInstance = null;
		}
	}

	/**
	 * register BroadcastReceiver to monitor USB events
	 * @throws IllegalStateException
	 */
	@SuppressLint({"UnspecifiedImmutableFlag", "WrongConstant"})
	public synchronized void register() throws IllegalStateException {
		if (destroyed) {
			XLogWrapper.e(TAG, "register failed: already destroyed");
			throw new IllegalStateException("already destroyed");
		}
		
		XLogWrapper.i(TAG, "register: initializing USB monitor");
		final Context context = mWeakContext.get();
		if (context == null) {
			XLogWrapper.e(TAG, "register failed: context is null");
			return;
		}
		
		// Always recreate the permission intent to ensure it's valid
		XLogWrapper.i(TAG, "register: creating permission intent");
		Intent intent = new Intent(ACTION_USB_PERMISSION);
		intent.setPackage(context.getPackageName());
		intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		XLogWrapper.i(TAG, "register: added FLAG_ACTIVITY_SINGLE_TOP and FLAG_ACTIVITY_CLEAR_TOP to intent");
		
		// Create PendingIntent with appropriate flags based on Android version
		try {
			int flags = PendingIntent.FLAG_UPDATE_CURRENT; // Always update existing intents
			
			if (Build.VERSION.SDK_INT >= 34) {
				// Android 14+ requires IMMUTABLE flag for security
				XLogWrapper.i(TAG, "register: Android 14+ detected, using FLAG_IMMUTABLE");
				flags |= PendingIntent.FLAG_IMMUTABLE;
			} else if (Build.VERSION.SDK_INT >= 31) {
				// Android 12+ requires explicit package name and MUTABLE flag
				XLogWrapper.i(TAG, "register: Android 12+ detected, using FLAG_MUTABLE");
				flags |= PendingIntent.FLAG_MUTABLE;
			}
			
			XLogWrapper.i(TAG, "register: creating PendingIntent with flags: " + flags);
			mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
			XLogWrapper.i(TAG, "register: permission intent created successfully");
		} catch (Exception e) {
			XLogWrapper.e(TAG, "register: failed to create permission intent", e);
			return;
		}
		
		// Create and register intent filter
		try {
			XLogWrapper.i(TAG, "register: creating intent filter");
			final IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
			// ACTION_USB_DEVICE_ATTACHED never comes on some devices so it should not be added here
			filter.addAction(ACTION_USB_DEVICE_ATTACHED);
			filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
			
			// Unregister existing receiver if it exists to avoid duplicate registration
			try {
				context.unregisterReceiver(mUsbReceiver);
				XLogWrapper.i(TAG, "register: unregistered existing receiver");
			} catch (IllegalArgumentException e) {
				// Receiver wasn't registered, this is expected on first run
				XLogWrapper.i(TAG, "register: no existing receiver to unregister");
			}
			
			// Register the receiver with appropriate flags
			if (Build.VERSION.SDK_INT >= 34) {
				// RECEIVER_NOT_EXPORTED is required on Android 14
				int RECEIVER_NOT_EXPORTED = 4;
				context.registerReceiver(mUsbReceiver, filter, RECEIVER_NOT_EXPORTED);
				XLogWrapper.i(TAG, "register: registered receiver with RECEIVER_NOT_EXPORTED flag");
			} else {
				context.registerReceiver(mUsbReceiver, filter);
				XLogWrapper.i(TAG, "register: registered receiver with default flags");
			}
		} catch (Exception e) {
			XLogWrapper.e(TAG, "register: failed to register receiver", e);
			return;
		}
		
		// Start connection check
		mDeviceCounts = 0;
		mAsyncHandler.postDelayed(mDeviceCheckRunnable, 1000);
		XLogWrapper.i(TAG, "register: USB monitor initialization complete, device check scheduled");
	}
	/**
	 * Unregister BroadcastReceiver to stop monitoring USB events
	 * @throws IllegalStateException
	 */
	public synchronized void unregister() throws IllegalStateException {
		XLogWrapper.i(TAG, "unregister: cleaning up USB monitor resources");
		
		// Stop device check runnable
		mDeviceCounts = 0;
		if (!destroyed) {
			XLogWrapper.i(TAG, "unregister: removing device check callbacks");
			mAsyncHandler.removeCallbacks(mDeviceCheckRunnable);
		}
		
		// Unregister broadcast receiver
		if (mPermissionIntent != null) {
			XLogWrapper.i(TAG, "unregister: permission intent exists, unregistering receiver");
			final Context context = mWeakContext.get();
			try {
				if (context != null) {
					context.unregisterReceiver(mUsbReceiver);
					XLogWrapper.i(TAG, "unregister: successfully unregistered USB receiver");
				} else {
					XLogWrapper.w(TAG, "unregister: context is null, cannot unregister receiver");
				}
			} catch (final Exception e) {
				XLogWrapper.e(TAG, "unregister: failed to unregister receiver", e);
			}
			
			// Clear permission intent
			mPermissionIntent = null;
			XLogWrapper.i(TAG, "unregister: cleared permission intent");
		}
	}

	public synchronized boolean isRegistered() {
		return !destroyed && (mPermissionIntent != null);
	}

	/**
	 * set device filter
	 * @param filter
	 * @throws IllegalStateException
	 */
	public void setDeviceFilter(final DeviceFilter filter) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		mDeviceFilters.clear();
		mDeviceFilters.add(filter);
	}

	/**
	 * デバイスフィルターを追加
	 * @param filter
	 * @throws IllegalStateException
	 */
	public void addDeviceFilter(final DeviceFilter filter) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		mDeviceFilters.add(filter);
	}

	/**
	 * デバイスフィルターを削除
	 * @param filter
	 * @throws IllegalStateException
	 */
	public void removeDeviceFilter(final DeviceFilter filter) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		mDeviceFilters.remove(filter);
	}

	/**
	 * set device filters
	 * @param filters
	 * @throws IllegalStateException
	 */
	public void setDeviceFilter(final List<DeviceFilter> filters) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		mDeviceFilters.clear();
		mDeviceFilters.addAll(filters);
	}

	/**
	 * add device filters
	 * @param filters
	 * @throws IllegalStateException
	 */
	public void addDeviceFilter(final List<DeviceFilter> filters) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		mDeviceFilters.addAll(filters);
	}

	/**
	 * remove device filters
	 * @param filters
	 */
	public void removeDeviceFilter(final List<DeviceFilter> filters) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		mDeviceFilters.removeAll(filters);
	}

	/**
	 * return the number of connected USB devices that matched device filter
	 * @return
	 * @throws IllegalStateException
	 */
	public int getDeviceCount() throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		return getDeviceList().size();
	}

	/**
	 * return device list, return empty list if no device matched
	 * @return
	 * @throws IllegalStateException
	 */
	public List<UsbDevice> getDeviceList() throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		return getDeviceList(mDeviceFilters);
	}

	/**
	 * return device list, return empty list if no device matched
	 * @param filters
	 * @return
	 * @throws IllegalStateException
	 */
	public List<UsbDevice> getDeviceList(final List<DeviceFilter> filters) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		// get detected devices
		final HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
		final List<UsbDevice> result = new ArrayList<UsbDevice>();
		if (deviceList != null) {
			if ((filters == null) || filters.isEmpty()) {
				result.addAll(deviceList.values());
			} else {
				for (final UsbDevice device: deviceList.values() ) {
					// match devices
					for (final DeviceFilter filter: filters) {
						if ((filter != null) && filter.matches(device) || (filter != null && filter.mSubclass == device.getDeviceSubclass())) {
							// when filter matches
							if (!filter.isExclude) {
								result.add(device);
							}
							break;
						}
					}
				}
			}
		}
		return result;
	}

	/**
	 * return device list, return empty list if no device matched
	 * @param filter
	 * @return
	 * @throws IllegalStateException
	 */
	public List<UsbDevice> getDeviceList(final DeviceFilter filter) throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		final HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
		final List<UsbDevice> result = new ArrayList<UsbDevice>();
		if (deviceList != null) {
			for (final UsbDevice device: deviceList.values() ) {
				if ((filter == null) || (filter.matches(device) && !filter.isExclude)) {
					result.add(device);
				}
			}
		}
		return result;
	}

	/**
	 * get USB device list, without filter
	 * @return
	 * @throws IllegalStateException
	 */
	public Iterator<UsbDevice> getDevices() throws IllegalStateException {
		if (destroyed) throw new IllegalStateException("already destroyed");
		Iterator<UsbDevice> iterator = null;
		final HashMap<String, UsbDevice> list = mUsbManager.getDeviceList();
		if (list != null)
			iterator = list.values().iterator();
		return iterator;
	}

	/**
	 * output device list to XLogWrapperCat
	 */
	public final void dumpDevices() {
		final HashMap<String, UsbDevice> list = mUsbManager.getDeviceList();
		if (list != null) {
			final Set<String> keys = list.keySet();
			if (keys != null && keys.size() > 0) {
				final StringBuilder sb = new StringBuilder();
				for (final String key: keys) {
					final UsbDevice device = list.get(key);
					final int num_interface = device != null ? device.getInterfaceCount() : 0;
					sb.setLength(0);
					for (int i = 0; i < num_interface; i++) {
						sb.append(String.format(Locale.US, "interface%d:%s", i, device.getInterface(i).toString()));
					}
					XLogWrapper.i(TAG, "key=" + key + ":" + device + ":" + sb.toString());
				}
			} else {
				XLogWrapper.i(TAG, "no device");
			}
		} else {
			XLogWrapper.i(TAG, "no device");
		}
	}

	/**
	 * return whether the specific Usb device has permission
	 * @param device
	 * @return true if the specified UsbDevice has permission
	 * @throws IllegalStateException
	 */
	public final boolean hasPermission(final UsbDevice device) throws IllegalStateException {
		
		if (device == null) {
			XLogWrapper.e(TAG, "hasPermission: device is null");
			return false;
		}
		
		if (destroyed) {
			XLogWrapper.e(TAG, "hasPermission failed: USBMonitor is destroyed");
			return false;
		}
		
		// Check cache first to avoid redundant system calls and logging
		try {
			final int deviceKey = getDeviceKey(device, true);
			synchronized (mHasPermissions) {
				WeakReference<UsbDevice> cachedDevice = mHasPermissions.get(deviceKey);
				if (cachedDevice != null && cachedDevice.get() != null) {
					// Device is in cache, check if system permission still matches
					boolean systemHasPermission = mUsbManager.hasPermission(device);
					if (systemHasPermission) {
						// Permission still valid, return true without excessive logging
						return true;
					} else {
						// Permission revoked, remove from cache and log
						if (DEBUG) XLogWrapper.i(TAG, "hasPermission: permission revoked for device: " + device.getDeviceName());
						mHasPermissions.remove(deviceKey);
						return false;
					}
				}
			}
		} catch (Exception e) {
			if (DEBUG) XLogWrapper.e(TAG, "hasPermission: Error checking cache: " + e.getLocalizedMessage());
		}
		
		// Not in cache or cache check failed, do full permission check
		if (DEBUG) XLogWrapper.i(TAG, "hasPermission checking for device: " + device.getDeviceName() + 
			", VendorId: " + device.getVendorId() + 
			", ProductId: " + device.getProductId());
		
		boolean systemHasPermission = mUsbManager.hasPermission(device);
		if (DEBUG) XLogWrapper.i(TAG, "hasPermission: system reports permission: " + systemHasPermission);
		
		// Update our internal permission state and return the result
		boolean result = updatePermission(device, systemHasPermission);
		if (DEBUG) XLogWrapper.i(TAG, "hasPermission: final result after updatePermission: " + result);
		return result;
	}

	/**
	 * Update internal permission state cache
	 * @param device USB device to update permission for
	 * @param hasPermission current permission state from system
	 * @return hasPermission (same as input)
	 */
	private boolean updatePermission(final UsbDevice device, final boolean hasPermission) {
		if (device == null) {
			XLogWrapper.e(TAG, "updatePermission: device is null");
			return false;
		}
		
		// fix api >= 29, permission SecurityException
		try {
			final int deviceKey = getDeviceKey(device, true);
			if (DEBUG) XLogWrapper.i(TAG, "updatePermission: device key = " + deviceKey);
			
			synchronized (mHasPermissions) {
				boolean wasInCache = mHasPermissions.get(deviceKey) != null;
				if (hasPermission) {
					if (!wasInCache) {
						if (DEBUG) XLogWrapper.i(TAG, "updatePermission: adding device to permission cache: " + device.getDeviceName());
						mHasPermissions.put(deviceKey, new WeakReference<UsbDevice>(device));
					} else {
						// Device already in cache, no need to log
						if (DEBUG) XLogWrapper.v(TAG, "updatePermission: device already in permission cache");
					}
				} else {
					if (wasInCache) {
						if (DEBUG) XLogWrapper.i(TAG, "updatePermission: removing device from permission cache: " + device.getDeviceName());
						mHasPermissions.remove(deviceKey);
					} else {
						// Device not in cache, no need to log
						if (DEBUG) XLogWrapper.v(TAG, "updatePermission: device not in permission cache");
					}
				}
			}
		} catch (SecurityException e) {
			XLogWrapper.e(TAG, "updatePermission: SecurityException: " + e.getLocalizedMessage(), e);
		} catch (Exception e) {
			XLogWrapper.e(TAG, "updatePermission: Exception: " + e.getLocalizedMessage(), e);
		}
		
		if (DEBUG) XLogWrapper.v(TAG, "updatePermission: returning " + hasPermission);
		return hasPermission;
	}
	
	/**
	 * Reset device permission state to ensure clean initialization
	 * @param device
	 */
	public synchronized void resetDevicePermission(final UsbDevice device) {
		
		if (device == null) {
			XLogWrapper.e(TAG, "resetDevicePermission: device is null");
			return;
		}
		
		XLogWrapper.i(TAG, "resetDevicePermission: " + device.getDeviceName() + 
			", VendorId: " + device.getVendorId() + 
			", ProductId: " + device.getProductId());
		
		// Clear cached permission state
		boolean previousPermission = mUsbManager.hasPermission(device);
		updatePermission(device, false);
		XLogWrapper.i(TAG, "resetDevicePermission: permission before reset: " + previousPermission + 
			", after reset: " + mUsbManager.hasPermission(device));
		
		// Remove from control blocks if present
		UsbControlBlock ctrlBlock = mCtrlBlocks.remove(device);
		if (ctrlBlock != null) {
			XLogWrapper.i(TAG, "resetDevicePermission: removing control block for device");
			ctrlBlock.close();
		} else {
			XLogWrapper.i(TAG, "resetDevicePermission: no control block found for device");
		}
		
		// Force garbage collection to clean up native resources
		System.gc();
		
		// Small delay to ensure cleanup is complete
		try {
			XLogWrapper.i(TAG, "resetDevicePermission: sleeping for 200ms to ensure cleanup");
			Thread.sleep(200);
			XLogWrapper.i(TAG, "resetDevicePermission: cleanup complete");
		} catch (InterruptedException e) {
			XLogWrapper.e(TAG, "resetDevicePermission: interrupted during cleanup", e);
			Thread.currentThread().interrupt();
		}
	}
	
	/**
     * Request permission to access USB device
     * @param device USB device to request permission for
     * @return true if request process successfully started or already have permission
     */
    public synchronized boolean requestPermission(final UsbDevice device) {
        XLogWrapper.v(TAG, "requestPermission: device=" + (device != null ? device.getDeviceName() : "null"));
        boolean result = false;
        
        // Check if USBMonitor is registered
        if (!isRegistered()) {
            XLogWrapper.e(TAG, "requestPermission failed: USBMonitor is not registered");
            XLogWrapper.e(TAG, "CRITICAL: Permission dialog will not show if USBMonitor is not registered");
            processCancel(device);
            return true;
        }
        
        // Check if device is valid
        if (device == null) {
            XLogWrapper.e(TAG, "requestPermission failed: device is null");
            processCancel(device);
            return true;
        }
        
        // Log device details
        XLogWrapper.i(TAG, "requestPermission for device: " + device.getDeviceName() + 
            ", VendorId: " + device.getVendorId() + 
            ", ProductId: " + device.getProductId());
        
        // Check if context is still valid
        final Context context = mWeakContext.get();
        if (context == null) {
            XLogWrapper.e(TAG, "requestPermission failed: context is null or has been garbage collected");
            XLogWrapper.e(TAG, "CRITICAL: Permission dialog cannot be shown without valid context");
            processCancel(device);
            return true;
        }
        
        // Check if this is a fresh install
        SharedPreferences prefs = context.getSharedPreferences("usb_monitor_prefs", Context.MODE_PRIVATE);
        boolean firstRun = prefs.getBoolean("first_run", true);
        if (firstRun) {
            XLogWrapper.w(TAG, "FIRST RUN DETECTED: This is a fresh install, ensuring permission flow is correct");
            // Mark that we've run once
            prefs.edit().putBoolean("first_run", false).apply();
        }
        
        // For Android 9+ (API 28+), reset permission first
        if (Build.VERSION.SDK_INT >= 28) {
            XLogWrapper.i(TAG, "Android 9+ detected, resetting device permission first");
            resetDevicePermission(device);
            try { 
                XLogWrapper.i(TAG, "Waiting 100ms after permission reset");
                Thread.sleep(100); 
            } catch (InterruptedException e) { 
                XLogWrapper.e(TAG, "Sleep interrupted", e);
                Thread.currentThread().interrupt(); 
            }
        }
        
        // Check if we already have permission
        boolean hasPermission = mUsbManager.hasPermission(device);
        XLogWrapper.i(TAG, "Current permission status from UsbManager: " + (hasPermission ? "GRANTED" : "DENIED"));
        
        // Double check our internal permission cache
        final int deviceKey = getDeviceKey(device, true);
        boolean inCache = false;
        synchronized (mHasPermissions) {
            inCache = mHasPermissions.get(deviceKey) != null;
        }
        XLogWrapper.i(TAG, "Permission status in internal cache: " + (inCache ? "GRANTED" : "DENIED") + ", deviceKey: " + deviceKey);
        
        // If there's a mismatch between system and our cache, log it
        if (hasPermission != inCache) {
            XLogWrapper.w(TAG, "PERMISSION STATE MISMATCH: UsbManager says " + 
                (hasPermission ? "GRANTED" : "DENIED") + " but our cache says " + 
                (inCache ? "GRANTED" : "DENIED"));
        }
        
        if (hasPermission) {
            XLogWrapper.i(TAG, "Permission already granted, processing connection");
            processConnect(device);
            return false;
        } else {
            // Need to request permission
            try {
                // Check if mPermissionIntent is properly initialized
                if (mPermissionIntent == null) {
                    XLogWrapper.e(TAG, "CRITICAL: Permission intent is null, cannot request permission");
                    XLogWrapper.e(TAG, "This usually means register() was not called or failed");
                    
                    // Try to recreate the permission intent as a last resort
                    XLogWrapper.i(TAG, "Attempting to recreate permission intent as emergency fix");
                    try {
                        Intent intent = new Intent(ACTION_USB_PERMISSION);
                        intent.setPackage(context.getPackageName());
                        // Add flags to prevent creating new activities
                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        
                        int flags = 0;
                        if (Build.VERSION.SDK_INT >= 31) {
                            flags = PendingIntent.FLAG_MUTABLE;
                        }
                        // Add FLAG_UPDATE_CURRENT to ensure we update any existing pending intent
                        flags |= PendingIntent.FLAG_UPDATE_CURRENT;
                        
                        mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
                        XLogWrapper.i(TAG, "Emergency permission intent creation succeeded with flags: " + flags);
                    } catch (Exception e) {
                        XLogWrapper.e(TAG, "Emergency permission intent creation failed", e);
                        processCancel(device);
                        return true;
                    }
                }
                
                // Check if the broadcast receiver is registered
                XLogWrapper.i(TAG, "Broadcast receiver status: " + (mUsbReceiver != null ? "initialized" : "NULL"));
                if (mUsbReceiver == null) {
                    XLogWrapper.e(TAG, "CRITICAL: USB receiver is null, permission result cannot be received");
                    processCancel(device);
                    return true;
                }
                
                // Log the current state before requesting permission
                XLogWrapper.i(TAG, "About to request USB permission with:" +
                    "\n - Device: " + device.getDeviceName() +
                    "\n - Permission Intent: " + mPermissionIntent +
                    "\n - UsbManager: " + mUsbManager +
                    "\n - Android version: " + Build.VERSION.SDK_INT);
                
                // Request permission
                mUsbManager.requestPermission(device, mPermissionIntent);
                XLogWrapper.i(TAG, "Permission request sent successfully");
                
                // On some devices, the permission dialog might not show immediately
                // Let's check if we get permission right away (this can happen on some devices)
                try { 
                    XLogWrapper.i(TAG, "Waiting 100ms to check if permission was granted immediately");
                    Thread.sleep(100); 
                    boolean immediatePermission = mUsbManager.hasPermission(device);
                    XLogWrapper.i(TAG, "Immediate permission check after request: " + 
                        (immediatePermission ? "GRANTED" : "DENIED/PENDING"));
                } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt(); 
                }
                
                return false;
            } catch (Exception e) {
                XLogWrapper.e(TAG, "requestPermission failed with exception", e);
                processCancel(device);
                return true;
            }
        }
    }
    
    /**
     * Open a USB device with permission check
     * @param device USB device to open
     * @return UsbControlBlock for the device
     * @throws SecurityException if permission is not granted
     */
    public UsbControlBlock openDevice(final UsbDevice device) throws SecurityException {
        if (device == null) {
            XLogWrapper.e(TAG, "openDevice: device is null");
            throw new IllegalArgumentException("device is null");
        }
        
        XLogWrapper.i(TAG, "openDevice: " + device.getDeviceName() + 
            ", VendorId: " + device.getVendorId() + 
            ", ProductId: " + device.getProductId());
        
        boolean hasPermission = hasPermission(device);
        XLogWrapper.i(TAG, "openDevice: has permission: " + hasPermission);
        
        if (hasPermission) {
            UsbControlBlock result = mCtrlBlocks.get(device);
            if (result == null) {
                XLogWrapper.i(TAG, "openDevice: creating new UsbControlBlock");
                try {
                    result = new UsbControlBlock(USBMonitor.this, device);
                    mCtrlBlocks.put(device, result);
                    XLogWrapper.i(TAG, "openDevice: successfully created UsbControlBlock");
                } catch (Exception e) {
                    XLogWrapper.e(TAG, "openDevice: failed to create UsbControlBlock", e);
                    throw new RuntimeException("Failed to open device: " + e.getMessage(), e);
                }
            } else {
                XLogWrapper.i(TAG, "openDevice: reusing existing UsbControlBlock");
            }
            return result;
        } else {
            XLogWrapper.e(TAG, "openDevice: no permission for device " + device.getDeviceName());
            throw new SecurityException("No permission to access USB device");
        }
    }

	/** number of connected & detected devices */
	private volatile int mDeviceCounts = 0;

	/**
	 * periodically check connected devices and if it changed, call onAttach
	 */
	private final Runnable mDeviceCheckRunnable = new Runnable() {
		@Override
		public void run() {
			if (destroyed) return;
			final List<UsbDevice> devices = getDeviceList();
			final int n = devices.size();
			final int hasPermissionCounts;
			final int m;
			
			// Only check permissions if device count changed or it's been a while
			boolean needsPermissionCheck = false;
			synchronized (mHasPermissions) {
				hasPermissionCounts = mHasPermissions.size();
				
				// Check if device count changed
				if (n != mDeviceCounts) {
					needsPermissionCheck = true;
					if (DEBUG) XLogWrapper.i(TAG, "Device count changed from " + mDeviceCounts + " to " + n + ", checking permissions");
				}
				
				// Only clear cache and recheck if needed
				if (needsPermissionCheck) {
					mHasPermissions.clear();
					for (final UsbDevice device: devices) {
						hasPermission(device);
					}
				} else {
					// Just verify existing permissions without clearing cache
					for (final UsbDevice device: devices) {
						hasPermission(device); // This will use cache optimization
					}
				}
				m = mHasPermissions.size();
			}
			if ((n > mDeviceCounts) || (m > hasPermissionCounts)) {
				mDeviceCounts = n;
				if (mOnDeviceConnectListener != null) {
					for (int i = 0; i < n; i++) {
						final UsbDevice device = devices.get(i);
						mAsyncHandler.post(new Runnable() {
							@Override
							public void run() {
								mOnDeviceConnectListener.onAttach(device);
							}
						});
					}
				}
			}
			
			// Use longer interval when devices are stable to reduce overhead
			long nextCheckDelay = needsPermissionCheck ? 2000 : 5000;
			mAsyncHandler.postDelayed(this, nextCheckDelay);
		}
	};

	/**
	 * open specific USB device
	 * @param device UsbDevice
	 */
	private void processConnect(final UsbDevice device) {
		if (destroyed) return;
		updatePermission(device, true);
		mAsyncHandler.post(() -> {
			if (DEBUG) XLogWrapper.v(TAG, "processConnect:device=" + device.getDeviceName());
			UsbControlBlock ctrlBlock;
			final boolean createNew;
			ctrlBlock = mCtrlBlocks.get(device);
			if (ctrlBlock == null) {
				ctrlBlock = new UsbControlBlock(USBMonitor.this, device);
				mCtrlBlocks.put(device, ctrlBlock);
				createNew = true;
			} else {
				createNew = false;
			}
			if (mOnDeviceConnectListener != null) {
				if (ctrlBlock.getConnection() == null) {
					XLogWrapper.e(TAG, "processConnect: Open device failed");
					mOnDeviceConnectListener.onCancel(device);
					return;
				}
				mOnDeviceConnectListener.onConnect(device, ctrlBlock, createNew);
			}
		});
	}
	
	/**
	 * Process permission denial or cancellation for a USB device
	 * @param device The USB device for which permission was denied or cancelled
	 */
	private void processCancel(final UsbDevice device) {
		if (destroyed) {
			XLogWrapper.w(TAG, "processCancel: USBMonitor already destroyed, ignoring");
			return;
		}
		
		XLogWrapper.i(TAG, "processCancel: Processing permission denial for device: " + 
			(device != null ? device.getDeviceName() + ", VendorId: " + device.getVendorId() + 
			", ProductId: " + device.getProductId() : "null"));
		
		// Check if this is a fresh install scenario
		SharedPreferences prefs = null;
		boolean isFirstRun = false;
		final Context context = mWeakContext.get();
		if (context != null) {
			prefs = context.getSharedPreferences("usb_monitor_prefs", Context.MODE_PRIVATE);
			isFirstRun = prefs.getBoolean("first_run", true);
			if (isFirstRun) {
				XLogWrapper.w(TAG, "processCancel: This appears to be a first run, permission dialog may not have been shown");
				prefs.edit().putBoolean("first_run", false).apply();
				
				// On first run, immediately try to request permission again
				if (device != null) {
					XLogWrapper.i(TAG, "processCancel: First run detected, trying to request permission again");
					mAsyncHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							requestPermission(device);
						}
					}, 500); // Small delay before requesting again
					return; // Don't notify about cancellation on first run
				}
			}
		}
		
		// Check if permission intent is valid
		XLogWrapper.i(TAG, "processCancel: Permission intent status: " + (mPermissionIntent != null ? "valid" : "NULL"));
		
		// Update internal permission state
		boolean previousPermission = device != null && mUsbManager.hasPermission(device);
		XLogWrapper.i(TAG, "processCancel: System permission before update: " + previousPermission);
		updatePermission(device, false);
		XLogWrapper.i(TAG, "processCancel: Updated internal permission cache to DENIED");
		
		// Notify listener about cancellation
		if (mOnDeviceConnectListener != null) {
			XLogWrapper.i(TAG, "processCancel: Notifying listener about cancellation");
			mAsyncHandler.post(new Runnable() {
				@Override
				public void run() {
					mOnDeviceConnectListener.onCancel(device);
					XLogWrapper.i(TAG, "processCancel: Listener notified");
				}
			});
		} else {
			XLogWrapper.w(TAG, "processCancel: No listener registered to notify about cancellation");
		}
	}

	private void processAttach(final UsbDevice device) {
		if (destroyed) return;
		if (DEBUG) XLogWrapper.v(TAG, "processAttach:");
		if (mOnDeviceConnectListener != null) {
			mAsyncHandler.post(new Runnable() {
				@Override
				public void run() {
					mOnDeviceConnectListener.onAttach(device);
				}
			});
		}
	}

	private void processDettach(final UsbDevice device) {
		if (destroyed) return;
		if (DEBUG) XLogWrapper.v(TAG, "processDettach:");
		if (mOnDeviceConnectListener != null) {
			mAsyncHandler.post(new Runnable() {
				@Override
				public void run() {
					mOnDeviceConnectListener.onDetach(device);
				}
			});
		}
	}

	/**
	 * USB機器毎の設定保存用にデバイスキー名を生成する。
	 * ベンダーID, プロダクトID, デバイスクラス, デバイスサブクラス, デバイスプロトコルから生成
	 * 同種の製品だと同じキー名になるので注意
	 * @param device nullなら空文字列を返す
	 * @return
	 */
	public static final String getDeviceKeyName(final UsbDevice device) {
		return getDeviceKeyName(device, null, false);
	}

	/**
	 * USB機器毎の設定保存用にデバイスキー名を生成する。
	 * useNewAPI=falseで同種の製品だと同じデバイスキーになるので注意
	 * @param device
	 * @param useNewAPI
	 * @return
	 */
	public static final String getDeviceKeyName(final UsbDevice device, final boolean useNewAPI) {
		return getDeviceKeyName(device, null, useNewAPI);
	}
	/**
	 * USB機器毎の設定保存用にデバイスキー名を生成する。この機器名をHashMapのキーにする
	 * UsbDeviceがopenしている時のみ有効
	 * ベンダーID, プロダクトID, デバイスクラス, デバイスサブクラス, デバイスプロトコルから生成
	 * serialがnullや空文字でなければserialを含めたデバイスキー名を生成する
	 * useNewAPI=trueでAPIレベルを満たしていればマニュファクチャ名, バージョン, コンフィギュレーションカウントも使う
	 * @param device nullなら空文字列を返す
	 * @param serial	UsbDeviceConnection#getSerialで取得したシリアル番号を渡す, nullでuseNewAPI=trueでAPI>=21なら内部で取得
	 * @param useNewAPI API>=21またはAPI>=23のみで使用可能なメソッドも使用する(ただし機器によってはnullが返ってくるので有効かどうかは機器による)
	 * @return
	 */
	@SuppressLint("NewApi")
	public static final String getDeviceKeyName(final UsbDevice device, final String serial, final boolean useNewAPI) {
		if (device == null) return "";
		final StringBuilder sb = new StringBuilder();
		sb.append(device.getVendorId());			sb.append("#");	// API >= 12
		sb.append(device.getProductId());			sb.append("#");	// API >= 12
		sb.append(device.getDeviceClass());			sb.append("#");	// API >= 12
		sb.append(device.getDeviceSubclass());		sb.append("#");	// API >= 12
		sb.append(device.getDeviceProtocol());						// API >= 12
		if (!TextUtils.isEmpty(serial)) {
			sb.append("#");	sb.append(serial);
		}
		if (useNewAPI && BuildCheck.isAndroid5()) {
			sb.append("#");
			if (TextUtils.isEmpty(serial)) {
				try { sb.append(device.getSerialNumber());	sb.append("#");	} // API >= 21 & targetSdkVersion has to be <= 28
				catch(SecurityException ignore) {}
			}
			sb.append(device.getManufacturerName());	sb.append("#");	// API >= 21
			sb.append(device.getConfigurationCount());	sb.append("#");	// API >= 21
			if (BuildCheck.isMarshmallow()) {
				sb.append(device.getVersion());			sb.append("#");	// API >= 23
			}
		}
//		if (DEBUG) XLogWrapper.v(TAG, "getDeviceKeyName:" + sb.toString());
		return sb.toString();
	}

	/**
	 * デバイスキーを整数として取得
	 * getDeviceKeyNameで得られる文字列のhasCodeを取得
	 * ベンダーID, プロダクトID, デバイスクラス, デバイスサブクラス, デバイスプロトコルから生成
	 * 同種の製品だと同じデバイスキーになるので注意
	 * @param device nullなら0を返す
	 * @return
	 */
	public static final int getDeviceKey(final UsbDevice device) {
		return device != null ? getDeviceKeyName(device, null, false).hashCode() : 0;
	}

	/**
	 * デバイスキーを整数として取得
	 * getDeviceKeyNameで得られる文字列のhasCodeを取得
	 * useNewAPI=falseで同種の製品だと同じデバイスキーになるので注意
	 * @param device
	 * @param useNewAPI
	 * @return
	 */
	public static final int getDeviceKey(final UsbDevice device, final boolean useNewAPI) {
		return device != null ? getDeviceKeyName(device, null, useNewAPI).hashCode() : 0;
	}

	/**
	 * デバイスキーを整数として取得
	 * getDeviceKeyNameで得られる文字列のhasCodeを取得
	 * serialがnullでuseNewAPI=falseで同種の製品だと同じデバイスキーになるので注意
	 * @param device nullなら0を返す
	 * @param serial UsbDeviceConnection#getSerialで取得したシリアル番号を渡す, nullでuseNewAPI=trueでAPI>=21なら内部で取得
	 * @param useNewAPI API>=21またはAPI>=23のみで使用可能なメソッドも使用する(ただし機器によってはnullが返ってくるので有効かどうかは機器による)
	 * @return
	 */
	public static final int getDeviceKey(final UsbDevice device, final String serial, final boolean useNewAPI) {
		return device != null ? getDeviceKeyName(device, serial, useNewAPI).hashCode() : 0;
	}

	public static class UsbDeviceInfo {
		public String usb_version;
		public String manufacturer;
		public String product;
		public String version;
		public String serial;

		private void clear() {
			usb_version = manufacturer = product = version = serial = null;
		}

		@Override
		public String toString() {
			return String.format("UsbDevice:usb_version=%s,manufacturer=%s,product=%s,version=%s,serial=%s",
				usb_version != null ? usb_version : "",
				manufacturer != null ? manufacturer : "",
				product != null ? product : "",
				version != null ? version : "",
				serial != null ? serial : "");
		}
	}

	private static final int USB_DIR_OUT = 0;
	private static final int USB_DIR_IN = 0x80;
	private static final int USB_TYPE_MASK = (0x03 << 5);
	private static final int USB_TYPE_STANDARD = (0x00 << 5);
	private static final int USB_TYPE_CLASS = (0x01 << 5);
	private static final int USB_TYPE_VENDOR = (0x02 << 5);
	private static final int USB_TYPE_RESERVED = (0x03 << 5);
	private static final int USB_RECIP_MASK = 0x1f;
	private static final int USB_RECIP_DEVICE = 0x00;
	private static final int USB_RECIP_INTERFACE = 0x01;
	private static final int USB_RECIP_ENDPOINT = 0x02;
	private static final int USB_RECIP_OTHER = 0x03;
	private static final int USB_RECIP_PORT = 0x04;
	private static final int USB_RECIP_RPIPE = 0x05;
	private static final int USB_REQ_GET_STATUS = 0x00;
	private static final int USB_REQ_CLEAR_FEATURE = 0x01;
	private static final int USB_REQ_SET_FEATURE = 0x03;
	private static final int USB_REQ_SET_ADDRESS = 0x05;
	private static final int USB_REQ_GET_DESCRIPTOR = 0x06;
	private static final int USB_REQ_SET_DESCRIPTOR = 0x07;
	private static final int USB_REQ_GET_CONFIGURATION = 0x08;
	private static final int USB_REQ_SET_CONFIGURATION = 0x09;
	private static final int USB_REQ_GET_INTERFACE = 0x0A;
	private static final int USB_REQ_SET_INTERFACE = 0x0B;
	private static final int USB_REQ_SYNCH_FRAME = 0x0C;
	private static final int USB_REQ_SET_SEL = 0x30;
	private static final int USB_REQ_SET_ISOCH_DELAY = 0x31;
	private static final int USB_REQ_SET_ENCRYPTION = 0x0D;
	private static final int USB_REQ_GET_ENCRYPTION = 0x0E;
	private static final int USB_REQ_RPIPE_ABORT = 0x0E;
	private static final int USB_REQ_SET_HANDSHAKE = 0x0F;
	private static final int USB_REQ_RPIPE_RESET = 0x0F;
	private static final int USB_REQ_GET_HANDSHAKE = 0x10;
	private static final int USB_REQ_SET_CONNECTION = 0x11;
	private static final int USB_REQ_SET_SECURITY_DATA = 0x12;
	private static final int USB_REQ_GET_SECURITY_DATA = 0x13;
	private static final int USB_REQ_SET_WUSB_DATA = 0x14;
	private static final int USB_REQ_LOOPBACK_DATA_WRITE = 0x15;
	private static final int USB_REQ_LOOPBACK_DATA_READ = 0x16;
	private static final int USB_REQ_SET_INTERFACE_DS = 0x17;

	private static final int USB_REQ_STANDARD_DEVICE_SET = (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_DEVICE);		// 0x10
	private static final int USB_REQ_STANDARD_DEVICE_GET = (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE);			// 0x90
	private static final int USB_REQ_STANDARD_INTERFACE_SET = (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_INTERFACE);	// 0x11
	private static final int USB_REQ_STANDARD_INTERFACE_GET = (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_INTERFACE);	// 0x91
	private static final int USB_REQ_STANDARD_ENDPOINT_SET = (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_ENDPOINT);	// 0x12
	private static final int USB_REQ_STANDARD_ENDPOINT_GET = (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_ENDPOINT);		// 0x92

	private static final int USB_REQ_CS_DEVICE_SET  = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_DEVICE);				// 0x20
	private static final int USB_REQ_CS_DEVICE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_DEVICE);					// 0xa0
	private static final int USB_REQ_CS_INTERFACE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE);			// 0x21
	private static final int USB_REQ_CS_INTERFACE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE);			// 0xa1
	private static final int USB_REQ_CS_ENDPOINT_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);				// 0x22
	private static final int USB_REQ_CS_ENDPOINT_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);				// 0xa2

	private static final int USB_REQ_VENDER_DEVICE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_DEVICE);				// 0x40
	private static final int USB_REQ_VENDER_DEVICE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_DEVICE);				// 0xc0
	private static final int USB_REQ_VENDER_INTERFACE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE);		// 0x41
	private static final int USB_REQ_VENDER_INTERFACE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE);		// 0xc1
	private static final int USB_REQ_VENDER_ENDPOINT_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);			// 0x42
	private static final int USB_REQ_VENDER_ENDPOINT_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);			// 0xc2

	private static final int USB_DT_DEVICE = 0x01;
	private static final int USB_DT_CONFIG = 0x02;
	private static final int USB_DT_STRING = 0x03;
	private static final int USB_DT_INTERFACE = 0x04;
	private static final int USB_DT_ENDPOINT = 0x05;
	private static final int USB_DT_DEVICE_QUALIFIER = 0x06;
	private static final int USB_DT_OTHER_SPEED_CONFIG = 0x07;
	private static final int USB_DT_INTERFACE_POWER = 0x08;
	private static final int USB_DT_OTG = 0x09;
	private static final int USB_DT_DEBUG = 0x0a;
	private static final int USB_DT_INTERFACE_ASSOCIATION = 0x0b;
	private static final int USB_DT_SECURITY = 0x0c;
	private static final int USB_DT_KEY = 0x0d;
	private static final int USB_DT_ENCRYPTION_TYPE = 0x0e;
	private static final int USB_DT_BOS = 0x0f;
	private static final int USB_DT_DEVICE_CAPABILITY = 0x10;
	private static final int USB_DT_WIRELESS_ENDPOINT_COMP = 0x11;
	private static final int USB_DT_WIRE_ADAPTER = 0x21;
	private static final int USB_DT_RPIPE = 0x22;
	private static final int USB_DT_CS_RADIO_CONTROL = 0x23;
	private static final int USB_DT_PIPE_USAGE = 0x24;
	private static final int USB_DT_SS_ENDPOINT_COMP = 0x30;
	private static final int USB_DT_CS_DEVICE = (USB_TYPE_CLASS | USB_DT_DEVICE);
	private static final int USB_DT_CS_CONFIG = (USB_TYPE_CLASS | USB_DT_CONFIG);
	private static final int USB_DT_CS_STRING = (USB_TYPE_CLASS | USB_DT_STRING);
	private static final int USB_DT_CS_INTERFACE = (USB_TYPE_CLASS | USB_DT_INTERFACE);
	private static final int USB_DT_CS_ENDPOINT = (USB_TYPE_CLASS | USB_DT_ENDPOINT);
	private static final int USB_DT_DEVICE_SIZE = 18;

	/**
	 * 指定したIDのStringディスクリプタから文字列を取得する。取得できなければnull
	 * @param connection
	 * @param id
	 * @param languageCount
	 * @param languages
	 * @return
	 */
	private static String getString(final UsbDeviceConnection connection, final int id, final int languageCount, final byte[] languages) {
		final byte[] work = new byte[256];
		String result = null;
		for (int i = 1; i <= languageCount; i++) {
			int ret = connection.controlTransfer(
				USB_REQ_STANDARD_DEVICE_GET, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE
				USB_REQ_GET_DESCRIPTOR,
				(USB_DT_STRING << 8) | id, languages[i], work, 256, 0);
			if ((ret > 2) && (work[0] == ret) && (work[1] == USB_DT_STRING)) {
				// skip first two bytes(bLength & bDescriptorType), and copy the rest to the string
				try {
					result = new String(work, 2, ret - 2, "UTF-16LE");
					if (!"Љ".equals(result)) {	// 変なゴミが返ってくる時がある
						break;
					} else {
						result = null;
					}
				} catch (final UnsupportedEncodingException e) {
					// ignore
				}
			}
		}
		return result;
	}

	/**
	 * ベンダー名・製品名・バージョン・シリアルを取得する
	 * @param device
	 * @return
	 */
	public UsbDeviceInfo getDeviceInfo(final UsbDevice device) {
		return updateDeviceInfo(mUsbManager, device, null);
	}

	/**
	 * ベンダー名・製品名・バージョン・シリアルを取得する
	 * #updateDeviceInfo(final UsbManager, final UsbDevice, final UsbDeviceInfo)のヘルパーメソッド
	 * @param context
	 * @param device
	 * @return
	 */
	public static UsbDeviceInfo getDeviceInfo(final Context context, final UsbDevice device) {
		return updateDeviceInfo((UsbManager)context.getSystemService(Context.USB_SERVICE), device, new UsbDeviceInfo());
	}

	/**
	 * ベンダー名・製品名・バージョン・シリアルを取得する
	 * @param manager
	 * @param device
	 * @param _info
	 * @return
	 */
	@TargetApi(Build.VERSION_CODES.M)
	public static UsbDeviceInfo updateDeviceInfo(final UsbManager manager, final UsbDevice device, final UsbDeviceInfo _info) {
		final UsbDeviceInfo info = _info != null ? _info : new UsbDeviceInfo();
		info.clear();

		if (device != null) {
			if (BuildCheck.isLollipop()) {
				info.manufacturer = device.getManufacturerName();
				info.product = device.getProductName();
				info.serial = device.getSerialNumber();
			}
			if (BuildCheck.isMarshmallow()) {
				info.usb_version = device.getVersion();
			}
			if ((manager != null) && manager.hasPermission(device)) {
				final UsbDeviceConnection connection = manager.openDevice(device);
				if(connection == null) {
					return null;
				}
				final byte[] desc = connection.getRawDescriptors();

				if (TextUtils.isEmpty(info.usb_version)) {
					info.usb_version = String.format("%x.%02x", ((int)desc[3] & 0xff), ((int)desc[2] & 0xff));
				}
				if (TextUtils.isEmpty(info.version)) {
					info.version = String.format("%x.%02x", ((int)desc[13] & 0xff), ((int)desc[12] & 0xff));
				}
				if (TextUtils.isEmpty(info.serial)) {
					info.serial = connection.getSerial();
				}

				final byte[] languages = new byte[256];
				int languageCount = 0;
				// controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout)
				try {
					int result = connection.controlTransfer(
						USB_REQ_STANDARD_DEVICE_GET, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE
	    				USB_REQ_GET_DESCRIPTOR,
	    				(USB_DT_STRING << 8) | 0, 0, languages, 256, 0);
					if (result > 0) {
	        			languageCount = (result - 2) / 2;
					}
					if (languageCount > 0) {
						if (TextUtils.isEmpty(info.manufacturer)) {
							info.manufacturer = getString(connection, desc[14], languageCount, languages);
						}
						if (TextUtils.isEmpty(info.product)) {
							info.product = getString(connection, desc[15], languageCount, languages);
						}
						if (TextUtils.isEmpty(info.serial)) {
							info.serial = getString(connection, desc[16], languageCount, languages);
						}
					}
				} finally {
					connection.close();
				}
			}
			if (TextUtils.isEmpty(info.manufacturer)) {
				info.manufacturer = USBVendorId.vendorName(device.getVendorId());
			}
			if (TextUtils.isEmpty(info.manufacturer)) {
				info.manufacturer = String.format("%04x", device.getVendorId());
			}
			if (TextUtils.isEmpty(info.product)) {
				info.product = String.format("%04x", device.getProductId());
			}
		}
		return info;
	}

	/**
	 * control class
	 * never reuse the instance when it closed
	 */
	public static final class UsbControlBlock implements Cloneable {
		private final WeakReference<USBMonitor> mWeakMonitor;
		private final WeakReference<UsbDevice> mWeakDevice;
		protected UsbDeviceConnection mConnection;
		protected final UsbDeviceInfo mInfo;
		private final int mBusNum;
		private final int mDevNum;
		private final SparseArray<SparseArray<UsbInterface>> mInterfaces = new SparseArray<SparseArray<UsbInterface>>();

		/**
		 * this class needs permission to access USB device before constructing
		 * @param monitor
		 * @param device
		 */
		private UsbControlBlock(final USBMonitor monitor, final UsbDevice device) {
			if (DEBUG) XLogWrapper.i(TAG, "UsbControlBlock:constructor");
			mWeakMonitor = new WeakReference<USBMonitor>(monitor);
			mWeakDevice = new WeakReference<UsbDevice>(device);
			mConnection = monitor.mUsbManager.openDevice(device);
			if (mConnection == null) {
				XLogWrapper.w(TAG, "openDevice failed in UsbControlBlock11, wait and try again");
				// Enhanced retry logic for higher Android versions
				int retryCount = 0;
				int maxRetries = Build.VERSION.SDK_INT >= 28 ? 5 : 3;
				long retryDelay = 500;
				
				while (mConnection == null && retryCount < maxRetries) {
					try {
						Thread.sleep(retryDelay);
						retryCount++;
						XLogWrapper.i(TAG, "Retry attempt " + retryCount + " for device: " + device.getDeviceName());
						mConnection = monitor.mUsbManager.openDevice(device);
						// Increase delay for subsequent retries
						retryDelay += 200;
					} catch (InterruptedException e) {
						XLogWrapper.e(TAG, "Interrupted during retry", e);
						break;
					}
				}
				
				if (mConnection == null) {
					XLogWrapper.e(TAG, "Failed to open device after " + maxRetries + " attempts");
				}
			}
			mInfo = updateDeviceInfo(monitor.mUsbManager, device, null);
			final String name = device.getDeviceName();
			final String[] v = !TextUtils.isEmpty(name) ? name.split("/") : null;
			int busnum = 0;
			int devnum = 0;
			if (v != null) {
				busnum = Integer.parseInt(v[v.length-2]);
				devnum = Integer.parseInt(v[v.length-1]);
			}
			mBusNum = busnum;
			mDevNum = devnum;
			if (DEBUG) {
				if (mConnection != null) {
					final int desc = mConnection.getFileDescriptor();
					final byte[] rawDesc = mConnection.getRawDescriptors();
					XLogWrapper.i(TAG, String.format(Locale.US, "name=%s,desc=%d,busnum=%d,devnum=%d,rawDesc=", name, desc, busnum, devnum));
				} else {
					XLogWrapper.e(TAG, "could not connect to device(mConnection=null) " + name);
				}
			}
		}

		/**
		 * copy constructor
		 * @param src
		 * @throws IllegalStateException
		 */
		private UsbControlBlock(final UsbControlBlock src) throws IllegalStateException {
			final USBMonitor monitor = src.getUSBMonitor();
			final UsbDevice device = src.getDevice();
			if (device == null) {
				throw new IllegalStateException("device may already be removed");
			}
			mConnection = monitor.mUsbManager.openDevice(device);
			if (mConnection == null) {
				XLogWrapper.w(TAG, "openDevice failed in UsbControlBlock, wait and try again");
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				mConnection = monitor.mUsbManager.openDevice(device);
				if (mConnection == null) {
					throw new IllegalStateException("openDevice failed. device may already be removed or have no permission, dev = " + device);
				}
			}
			mInfo = updateDeviceInfo(monitor.mUsbManager, device, null);
			mWeakMonitor = new WeakReference<USBMonitor>(monitor);
			mWeakDevice = new WeakReference<UsbDevice>(device);
			mBusNum = src.mBusNum;
			mDevNum = src.mDevNum;
			// FIXME USBMonitor.mCtrlBlocksに追加する(今はHashMapなので追加すると置き換わってしまうのでだめ, ListかHashMapにListをぶら下げる?)
		}

		/**
		 * duplicate by clone
		 * need permission
		 * USBMonitor never handle cloned UsbControlBlock, you should release it after using it.
		 * @return
		 * @throws CloneNotSupportedException
		 */
		@Override
		public UsbControlBlock clone() throws CloneNotSupportedException {
			final UsbControlBlock ctrlblock;
			try {
				ctrlblock = new UsbControlBlock(this);
			} catch (final IllegalStateException e) {
				throw new CloneNotSupportedException(e.getMessage());
			}
			return ctrlblock;
		}

		public USBMonitor getUSBMonitor() {
			return mWeakMonitor.get();
		}

		public final UsbDevice getDevice() {
			return mWeakDevice.get();
		}

		/**
		 * get device name
		 * @return
		 */
		public String getDeviceName() {
			final UsbDevice device = mWeakDevice.get();
			return device != null ? device.getDeviceName() : "";
		}

		/**
		 * get device id
		 * @return
		 */
		public int getDeviceId() {
			final UsbDevice device = mWeakDevice.get();
			return device != null ? device.getDeviceId() : 0;
		}

		/**
		 * get device key string
		 * @return same value if the devices has same vendor id, product id, device class, device subclass and device protocol
		 */
		public String getDeviceKeyName() {
			return USBMonitor.getDeviceKeyName(mWeakDevice.get());
		}

		/**
		 * get device key string
		 * @param useNewAPI if true, try to use serial number
		 * @return
		 * @throws IllegalStateException
		 */
		public String getDeviceKeyName(final boolean useNewAPI) throws IllegalStateException {
			if (useNewAPI) checkConnection();
			return USBMonitor.getDeviceKeyName(mWeakDevice.get(), mInfo.serial, useNewAPI);
		}

		/**
		 * get device key
		 * @return
		 * @throws IllegalStateException
		 */
		public int getDeviceKey() throws IllegalStateException {
			checkConnection();
			return USBMonitor.getDeviceKey(mWeakDevice.get());
		}

		/**
		 * get device key
		 * @param useNewAPI if true, try to use serial number
		 * @return
		 * @throws IllegalStateException
		 */
		public int getDeviceKey(final boolean useNewAPI) throws IllegalStateException {
			if (useNewAPI) checkConnection();
			return USBMonitor.getDeviceKey(mWeakDevice.get(), mInfo.serial, useNewAPI);
		}

		/**
		 * get device key string
		 * if device has serial number, use it
		 * @return
		 */
		public String getDeviceKeyNameWithSerial() {
			return USBMonitor.getDeviceKeyName(mWeakDevice.get(), mInfo.serial, false);
		}

		/**
		 * get device key
		 * if device has serial number, use it
		 * @return
		 */
		public int getDeviceKeyWithSerial() {
			return getDeviceKeyNameWithSerial().hashCode();
		}

		/**
		 * get UsbDeviceConnection
		 * @return
		 */
		public synchronized UsbDeviceConnection getConnection() {
			return mConnection;
		}

		/**
		 * get file descriptor to access USB device
		 * @return
		 * @throws IllegalStateException
		 */
		public synchronized int getFileDescriptor() throws IllegalStateException {
			checkConnection();
			return mConnection.getFileDescriptor();
		}

		/**
		 * get raw descriptor for the USB device
		 * @return
		 * @throws IllegalStateException
		 */
		public synchronized byte[] getRawDescriptors() throws IllegalStateException {
			checkConnection();
			return mConnection.getRawDescriptors();
		}

		/**
		 * get vendor id
		 * @return
		 */
		public int getVenderId() {
			final UsbDevice device = mWeakDevice.get();
			return device != null ? device.getVendorId() : 0;
		}

		/**
		 * get product id
		 * @return
		 */
		public int getProductId() {
			final UsbDevice device = mWeakDevice.get();
			return device != null ? device.getProductId() : 0;
		}

		/**
		 * get version string of USB
		 * @return
		 */
		public String getUsbVersion() {
			return mInfo.usb_version;
		}

		/**
		 * get manufacture
		 * @return
		 */
		public String getManufacture() {
			return mInfo.manufacturer;
		}

		/**
		 * get product name
		 * @return
		 */
		public String getProductName() {
			return mInfo.product;
		}

		/**
		 * get version
		 * @return
		 */
		public String getVersion() {
			return mInfo.version;
		}

		/**
		 * get serial number
		 * @return
		 */
		public String getSerial() {
			return mInfo.serial;
		}

		public int getBusNum() {
			return mBusNum;
		}

		public int getDevNum() {
			return mDevNum;
		}

		/**
		 * get interface
		 * @param interface_id
		 * @throws IllegalStateException
		 */
		public synchronized UsbInterface getInterface(final int interface_id) throws IllegalStateException {
			return getInterface(interface_id, 0);
		}

		/**
		 * get interface
		 * @param interface_id
		 * @param altsetting
		 * @return
		 * @throws IllegalStateException
		 */
		@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
		public synchronized UsbInterface getInterface(final int interface_id, final int altsetting) throws IllegalStateException {
			checkConnection();
			SparseArray<UsbInterface> intfs = mInterfaces.get(interface_id);
			if (intfs == null) {
				intfs = new SparseArray<UsbInterface>();
				mInterfaces.put(interface_id, intfs);
			}
			UsbInterface intf = intfs.get(altsetting);
			if (intf == null) {
				final UsbDevice device = mWeakDevice.get();
				final int n = device.getInterfaceCount();
				for (int i = 0; i < n; i++) {
					final UsbInterface temp = device.getInterface(i);
					if ((temp.getId() == interface_id) && (temp.getAlternateSetting() == altsetting)) {
						intf = temp;
						break;
					}
				}
				if (intf != null) {
					intfs.append(altsetting, intf);
				}
			}
			return intf;
		}

		/**
		 * open specific interface
		 * @param intf
		 */
		public synchronized void claimInterface(final UsbInterface intf) {
			claimInterface(intf, true);
		}

		public synchronized void claimInterface(final UsbInterface intf, final boolean force) {
			checkConnection();
			mConnection.claimInterface(intf, force);
		}

		/**
		 * close interface
		 * @param intf
		 * @throws IllegalStateException
		 */
		public synchronized void releaseInterface(final UsbInterface intf) throws IllegalStateException {
			checkConnection();
			final SparseArray<UsbInterface> intfs = mInterfaces.get(intf.getId());
			if (intfs != null) {
				final int index = intfs.indexOfValue(intf);
				intfs.removeAt(index);
				if (intfs.size() == 0) {
					mInterfaces.remove(intf.getId());
				}
			}
			mConnection.releaseInterface(intf);
		}

		/**
		 * Close device
		 * This also close interfaces if they are opened in Java side
		 */
		public synchronized void close() {
			if (DEBUG) XLogWrapper.i(TAG, "UsbControlBlock#close:");

			if (mConnection != null) {
				final int n = mInterfaces.size();
				for (int i = 0; i < n; i++) {
					final SparseArray<UsbInterface> intfs = mInterfaces.valueAt(i);
					if (intfs != null) {
						final int m = intfs.size();
						for (int j = 0; j < m; j++) {
							final UsbInterface intf = intfs.valueAt(j);
							mConnection.releaseInterface(intf);
						}
						intfs.clear();
					}
				}
				mInterfaces.clear();
				mConnection.close();
				mConnection = null;
				final USBMonitor monitor = mWeakMonitor.get();
				if (monitor != null) {
					if (monitor.mOnDeviceConnectListener != null) {
						monitor.mOnDeviceConnectListener.onDisconnect(mWeakDevice.get(), UsbControlBlock.this);
					}
					monitor.mCtrlBlocks.remove(getDevice());
				}
			}
		}

		@Override
		public boolean equals(final Object o) {
			if (o == null) return false;
			if (o instanceof UsbControlBlock) {
				final UsbDevice device = ((UsbControlBlock) o).getDevice();
				return device == null ? mWeakDevice.get() == null
						: device.equals(mWeakDevice.get());
			} else if (o instanceof UsbDevice) {
				return o.equals(mWeakDevice.get());
			}
			return super.equals(o);
		}

//		@Override
//		protected void finalize() throws Throwable {
///			close();
//			super.finalize();
//		}

		private synchronized void checkConnection() throws IllegalStateException {
			if (mConnection == null) {
				throw new IllegalStateException("already closed");
			}
		}
	}

}
