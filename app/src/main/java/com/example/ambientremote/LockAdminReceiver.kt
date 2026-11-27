package com.example.ambientremote

import android.app.admin.DeviceAdminReceiver

/** Lets the app lock the screen ("Enter Lock Screen"). Only the force-lock policy is requested. */
class LockAdminReceiver : DeviceAdminReceiver()
