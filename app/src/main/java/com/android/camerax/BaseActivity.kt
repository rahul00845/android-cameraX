package com.android.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.SparseIntArray
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker

@SuppressLint("Registered")
open class BaseActivity : AppCompatActivity() {

    private var mErrorString: SparseIntArray? = null
    private var onPermissionResult = { _: Int, _: Boolean-> Unit }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mErrorString = SparseIntArray()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var permissionCheck = PackageManager.PERMISSION_GRANTED
        if (grantResults.isNotEmpty()){
            for (permission in grantResults) {
                permissionCheck += permission
            }
            if (grantResults.isNotEmpty() && permissionCheck == PackageManager.PERMISSION_GRANTED) {
                onPermissionResult.invoke(requestCode, true)
            } else {
                for(permission in permissions) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        //Show permission explanation dialog...
                        onPermissionResult.invoke(requestCode, false)
                        break
                    }else{
                        //Never ask again selected, or device policy prohibits the app from having that permission.
                        //So, disable that feature, or fall back to another situation...
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                        break
                    }
                }
            }
        }
    }

    fun requestAppPermissions(
        requestedPermissions: Array<String>,
        requestCode: Int,
        onPermissionResult: (requestCode: Int, permissionStatus: Boolean) -> Unit
    ) {
        this.onPermissionResult = onPermissionResult
        val permissionList = ArrayList<String>()
        for (permission in requestedPermissions) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PermissionChecker.PERMISSION_GRANTED
            ) {
                permissionList.add(permission)
            }
        }
        if (permissionList.size > 0) {
            ActivityCompat.requestPermissions(
                this,
                requestedPermissions, requestCode
            )
        } else {
            onPermissionResult.invoke(requestCode, true)
        }
    }
}
