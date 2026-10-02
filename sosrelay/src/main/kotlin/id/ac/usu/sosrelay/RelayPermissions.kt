package id.ac.usu.sosrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The host requests these runtime permissions; the library never opens a dialog. */
object RelayPermissions {
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): List<String> = required().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
