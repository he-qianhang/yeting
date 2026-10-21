package cn.yeting.app

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Counts real View API requests, not physical vibrations. All counters delegate to super. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TouchFeedbackDeviceTest {
    private val inst get()=DeviceFixture.inst
    private val device get()=UiDevice.getInstance(inst)
    private val prefs get()=inst.targetContext.getSharedPreferences("night",Context.MODE_PRIVATE)

    @Before fun qaOnly(){
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim())
        DeviceFixture.requireQaEmulator()
    }

    private class CountingView(context:Context):View(context) {
        var calls=0
        var lastFlags=0
        override fun performHapticFeedback(feedbackConstant:Int,flags:Int):Boolean {
            calls++;lastFlags=flags
            return super.performHapticFeedback(feedbackConstant,flags)
        }
    }
    private class CountingRuler(context:Context):ListeningRuler(context,15,{it.toString()},{it%5==0}) {
        var calls=0
        override fun performHapticFeedback(feedbackConstant:Int):Boolean {
            calls++
            return super.performHapticFeedback(feedbackConstant)
        }
    }
    private class CountingPicker(context:Context):ListeningNumberPicker(context) {
        var calls=0
        override fun performHapticFeedback(feedbackConstant:Int):Boolean {
            calls++
            return super.performHapticFeedback(feedbackConstant)
        }
    }

    private fun <T> ui(block:()->T):T = DeviceFixture.ui(block)
    private fun ready(view:View):Boolean = view.isAttachedToWindow && view.isShown &&
        view.hasWindowFocus() && view.isEnabled && view.isHapticFeedbackEnabled && view.width>0 && view.height>0

    private fun <T:View> attach(root:LinearLayout,view:T,height:Int=180):T {
        ui{
            root.addView(view,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,height))
            view.isFocusableInTouchMode=true;view.requestFocus()
        }
        DeviceFixture.await{ui{ready(view)}}
        return view
    }

    private fun withWindow(block:(LinearLayout)->Unit) {
        val saved=listOf("haptics","autoClipboard").associateWith{prefs.all[it]}
        var created:MainActivity?=null
        var dialog:Dialog?=null
        try {
            assertTrue(prefs.edit().remove("haptics").putBoolean("autoClipboard",false).commit())
            val existing=ui{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()}
            val activity=existing ?: (inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity).also{created=it}
            val root=ui{LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}}
            ui{
                dialog=Dialog(activity).apply{
                    setContentView(root);show()
                    window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            DeviceFixture.await{ui{root.isAttachedToWindow && root.isShown && root.hasWindowFocus()}}
            block(root)
        } finally {
            try {ui{dialog?.dismiss();created?.finish()}}
            finally {
                val edit=prefs.edit()
                saved.forEach{(key,value)->if(value==null)edit.remove(key)else edit.putBoolean(key,value as Boolean)}
                assertTrue("Restore original haptic and clipboard preferences",edit.commit())
            }
        }
    }

    private fun setProgress(ruler:ListeningRuler,value:Int):Boolean = ruler.performAccessibilityAction(
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
        Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value.toFloat())})

    @Test fun ticksReachAttachedVisibleFocusedViewWithDefaultPreference() = withWindow {root->
        val view=attach(root,ui{CountingView(root.context)})
        ui{
            assertTrue(ready(view));assertTrue(view.isFocused);assertFalse(prefs.contains("haptics"))
            val ticks=TouchFeedback.Ticks().apply{reset(0)}
            ticks.tick(view,1)
            assertEquals(1,view.calls);assertEquals(0,view.lastFlags)
            // A request count does not depend on hardware support or the system setting.
        }
    }

    @Test fun disabledPreferenceAndViewSettingSuppressRequests() = withWindow {root->
        val view=attach(root,ui{CountingView(root.context)})
        ui{
            assertTrue(prefs.edit().putBoolean("haptics",false).commit())
            assertFalse(TouchFeedback.Ticks().tick(view,1))
            assertFalse(TouchFeedback.drop(view))
            assertFalse(TouchFeedback.threshold(view));assertEquals(0,view.calls)
            assertTrue(prefs.edit().putBoolean("haptics",true).commit())
            view.isHapticFeedbackEnabled=false
            assertFalse(TouchFeedback.Ticks().tick(view,2));assertEquals(0,view.calls)
            view.isHapticFeedbackEnabled=true
            TouchFeedback.Ticks().tick(view,3)
            assertEquals(1,view.calls);assertEquals(0,view.lastFlags)
        }
    }

    @Test fun programmaticRulerProgressAndPickerSetValueStaySilent() = withWindow {root->
        val ruler=attach(root,ui{CountingRuler(root.context).apply{progress=2}})
        val picker=attach(root,ui{CountingPicker(root.context).apply{
            minValue=1;maxValue=99;value=4;wrapSelectorWheel=false
            descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }},300)
        ui{
            var userChanges=0;var programChanges=0;var pickerChanges=0;var nativeChanges=0
            ruler.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar:SeekBar){}
                override fun onStopTrackingTouch(bar:SeekBar){}
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){
                    if(fromUser){userChanges++;ruler.tickFeedback()}else programChanges++
                }
            })
            picker.setOnValueChangedListener{_,_,_->pickerChanges++}
            val native=NumberPicker(root.context).apply{minValue=1;maxValue=99;value=4}
            native.setOnValueChangedListener{_,_,_->nativeChanges++}
            ruler.calls=0;picker.calls=0
            ruler.progress=3;ruler.progress=3
            picker.value=7;picker.value=7;native.value=7
            assertEquals(3,ruler.progress);assertEquals(1,programChanges);assertEquals(0,userChanges)
            assertEquals(7,picker.value);assertEquals(0,pickerChanges);assertEquals(0,nativeChanges)
            assertEquals(0,ruler.calls);assertEquals(0,picker.calls)
        }
    }

    @Test fun accessibilitySetProgressEmitsExactlyOneRulerRequest() = withWindow {root->
        val ruler=attach(root,ui{CountingRuler(root.context).apply{progress=4}})
        ui{
            var userChanges=0
            ruler.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar:SeekBar){}
                override fun onStopTrackingTouch(bar:SeekBar){}
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){
                    if(fromUser){userChanges++;ruler.tickFeedback()}
                }
            })
            ruler.calls=0
            assertTrue(setProgress(ruler,5))
            assertEquals(5,ruler.progress);assertEquals(1,userChanges);assertEquals(1,ruler.calls)
            assertTrue(prefs.edit().putBoolean("haptics",false).commit())
            assertTrue(setProgress(ruler,6))
            assertEquals(2,userChanges);assertEquals(1,ruler.calls)
        }
    }

    @Test fun systemOffIsEnforcedByRealPlatformService() {
        // API 36 posts asynchronously and may return true even when the service suppresses it.
        assumeTrue("Service-history assertions below target Android 16+",Build.VERSION.SDK_INT>=36)
        assumeTrue("No physical/virtual vibrator: request counts cannot verify system suppression",
            inst.targetContext.getSystemService(Vibrator::class.java)?.hasVibrator()==true)
        withWindow {root->
            val view=attach(root,ui{CountingView(root.context)})
            val resolver=inst.targetContext.contentResolver
            val key=Settings.System.HAPTIC_FEEDBACK_ENABLED
            val previous=Settings.System.getString(resolver,key)
            assertTrue("Unexpected system haptic value",previous==null || previous=="0" || previous=="1")
            try {
                device.executeShellCommand("settings --user current put system $key 0")
                DeviceFixture.await{Settings.System.getString(resolver,key)=="0"}
                // Allow the service's settings observer to process the change before the single request.
                SystemClock.sleep(500)
                val before=device.executeShellCommand("dumpsys vibrator_manager").lineSequence().map{it.trim()}.toSet()
                ui{
                    assertTrue(ready(view))
                    TouchFeedback.threshold(view)
                    assertEquals(1,view.calls);assertEquals(0,view.lastFlags)
                }
                var lastDump=""
                try {
                    DeviceFixture.await(5000){
                        lastDump=device.executeShellCommand("dumpsys vibrator_manager")
                        lastDump.lineSequence().map{it.trim()}.any{line->
                            line !in before && line.contains("opPkg=${inst.targetContext.packageName},") &&
                                line.contains("ViewRootImpl#performHapticFeedback") &&
                                line.contains("status: ignored_for_settings")
                        }
                    }
                } catch(failure:AssertionError){
                    throw AssertionError("No fresh platform settings-suppression record; do not infer it from View return values.\n"+
                        lastDump.takeLast(6000),failure)
                }
                assertEquals("0",Settings.System.getString(resolver,key))
            } finally {
                if(previous==null)device.executeShellCommand("settings --user current delete system $key")
                else device.executeShellCommand("settings --user current put system $key $previous")
                DeviceFixture.await{Settings.System.getString(resolver,key)==previous}
            }
        }
    }
}
