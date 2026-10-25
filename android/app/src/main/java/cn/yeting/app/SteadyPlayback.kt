package cn.yeting.app

import android.content.Context
import android.os.Handler
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.*
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.*
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SteadyPlayback {
    @Volatile var profile:SteadyProfile?=null;private set
    @Volatile var inputEntry:String?=null;private set
    @Volatile var enabled=false
    @Volatile var mix=0.0;private set
    @Volatile var outputObserver:((ByteBuffer)->Unit)?=null
    var selected:((String)->Unit)?=null
    private val profiles=ConcurrentHashMap<String,SteadyProfile>()
    private var selectedTag=""
    private var selectedKey=""
    private var boundary=0L
    @Volatile private var playbackRequest=0L
    fun beginPlayback(){playbackRequest++}
    private val boundaries=mutableListOf<String>()
    fun boundaryEntries()=synchronized(boundaries){boundaries.toList()}
    private fun key(entry:Entry):String {
        val file=NightApp.store.audioFile(entry)
        return entry.id+"|"+file.absolutePath+"|"+file.length()+"|"+file.lastModified()
    }
    fun publish(id:String){
        val entry=NightApp.store.get(id) ?: return
        val current=NightApp.steady.profile(id) ?: return
        profiles[key(entry)]=current
    }
    @JvmName("select") internal fun select(format:Format){
        val tag=format.label.orEmpty()
        if(tag==selectedTag)return
        selectedTag=tag
        val json=JSONObject(tag.removePrefix("yeting:"))
        inputEntry=json.getString("id");selectedKey=json.optString("key",inputEntry!!)
        // Direct processor QA supplies an explicit snapshot; production tags contain identity only.
        if(json.has("enabled")){
            enabled=json.getBoolean("enabled")
            if(enabled)profiles[selectedKey]=SteadyProfile(json.getDouble("pre"),json.getDouble("post"),json.optBoolean("limited"))
        }
        boundary++
        synchronized(boundaries){boundaries.add(inputEntry!!);if(boundaries.size>32)boundaries.removeAt(0)}
        selected?.invoke(inputEntry!!)
    }
    fun sourceFactory(context:Context)=object:MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider:DrmSessionManagerProvider)=this
        override fun setLoadErrorHandlingPolicy(policy:LoadErrorHandlingPolicy)=this
        override fun getSupportedTypes()=intArrayOf(C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(item:MediaItem):MediaSource {
            val entry=checkNotNull(NightApp.store.get(item.mediaId))
            val identity=key(entry)
            NightApp.steady.profile(item.mediaId)?.let{profiles[identity]=it}
            val tag=JSONObject().put("id",item.mediaId).put("key",identity).toString()
            val factory=object:ExtractorsFactory {
                override fun createExtractors()=DefaultExtractorsFactory().createExtractors().map{extractor->
                    object:Extractor by extractor {
                        override fun init(output:ExtractorOutput){
                            extractor.init(object:ExtractorOutput by output {
                                override fun track(id:Int,type:Int):TrackOutput {
                                    val track=output.track(id,type)
                                    return if(type!=C.TRACK_TYPE_AUDIO)track else object:TrackOutput by track {
                                        override fun format(format:Format){track.format(format.buildUpon().setLabel("yeting:$tag").build())}
                                    }
                                }
                            })
                        }
                    }
                }.toTypedArray()
            }
            return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context),factory).createMediaSource(item)
        }
    }

    fun renderers(context:Context)=object:DefaultRenderersFactory(context) {
        override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink {
            val sink=DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false)
                .setAudioProcessorChain(Chain()).build()
            return object:ForwardingAudioSink(sink){
                override fun supportsFormat(format:Format)=format.sampleMimeType==MimeTypes.AUDIO_RAW && super.supportsFormat(format)
                override fun getFormatSupport(format:Format)=if(format.sampleMimeType==MimeTypes.AUDIO_RAW)super.getFormatSupport(format)else AudioSink.SINK_FORMAT_UNSUPPORTED
                override fun getFormatOffloadSupport(format:Format)=AudioOffloadSupport.DEFAULT_UNSUPPORTED
            }
        }
        override fun buildAudioRenderers(context:Context,extensionRendererMode:Int,mediaCodecSelector:MediaCodecSelector,
            enableDecoderFallback:Boolean,audioSink:AudioSink,eventHandler:Handler,eventListener:AudioRendererEventListener,out:ArrayList<Renderer>){
            out.add(object:MediaCodecAudioRenderer(context,codecAdapterFactory,mediaCodecSelector,enableDecoderFallback,eventHandler,eventListener,audioSink){
                override fun processOutputBuffer(positionUs:Long,elapsedRealtimeUs:Long,codec:MediaCodecAdapter?,buffer:ByteBuffer?,
                    bufferIndex:Int,bufferFlags:Int,sampleCount:Int,bufferPresentationTimeUs:Long,isDecodeOnlyBuffer:Boolean,isLastBuffer:Boolean,format:Format):Boolean {
                    // Format follows each decoded buffer, including decoder reuse and predecoded next entries.
                    if(!isDecodeOnlyBuffer)select(format)
                    return super.processOutputBuffer(positionUs,elapsedRealtimeUs,codec,buffer,bufferIndex,bufferFlags,sampleCount,
                        bufferPresentationTimeUs,isDecodeOnlyBuffer,isLastBuffer,format)
                }
            })
        }
    }

    internal inner class Chain:AudioProcessorChain {
        val sonic=SonicAudioProcessor()
        private var speed=1f
        val input=InputProcessor{speed}
        val output=OutputProcessor()
        override fun getAudioProcessors()=arrayOf<AudioProcessor>(input,sonic,output)
        override fun applyPlaybackParameters(parameters:PlaybackParameters):PlaybackParameters {speed=parameters.speed;sonic.setSpeed(parameters.speed);sonic.setPitch(parameters.pitch);return parameters}
        override fun applySkipSilenceEnabled(skipSilenceEnabled:Boolean)=false
        override fun getMediaDuration(playoutDuration:Long)=if(sonic.isActive)sonic.getMediaDuration(playoutDuration)else playoutDuration
        override fun getSkippedOutputFrameCount()=0L
    }

    // Original, processed, and opposite control channels travel through ONE Sonic instance.
    // This preserves alignment at every speed. The two controls cancel in Sonic's mono detector.
    internal inner class InputProcessor(private val speed:()->Float={1f}):BaseAudioProcessor() {
        private lateinit var compressor:SteadyCompressor
        private lateinit var limiter:SteadyPeakLimiter
        private lateinit var delay:SteadyFrameDelay
        private lateinit var transition:SteadyTransition
        private var input=FloatArray(0)
        private var compressed=FloatArray(0)
        private var limited=FloatArray(0)
        private var original=FloatArray(0)
        private var delayed=FloatArray(0)
        private var framesIn=0L
        private var framesOut=0L
        private var seenBoundary=-1L
        private var seenPlaybackRequest=-1L
        private var lastProfile:SteadyProfile?=null
        private var wetStarted=false
        private var transitionRate=0
        private var transitionChannels=0
        private var drained=false
        override fun onConfigure(format:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
            if(format.encoding!=C.ENCODING_PCM_16BIT)throw AudioProcessor.UnhandledAudioFormatException(format)
            return AudioProcessor.AudioFormat(format.sampleRate,format.channelCount*2+2,format.encoding)
        }
        override fun onFlush(){
            val rate=inputAudioFormat.sampleRate;val channels=inputAudioFormat.channelCount
            compressor=SteadyCompressor(rate,channels);limiter=SteadyPeakLimiter(rate,channels,.98f)
            delay=SteadyFrameDelay(channels+1,compressor.delayFrames+limiter.delayFrames)
            if(rate!=transitionRate || channels!=transitionChannels){
                transition=SteadyTransition(rate);transitionRate=rate;transitionChannels=channels;seenBoundary=-1;seenPlaybackRequest=-1;lastProfile=null
            }
            framesIn=0;framesOut=0;drained=false;wetStarted=false
            input=FloatArray(channels);compressed=input.copyOf();limited=input.copyOf()
            original=FloatArray(channels+1);delayed=original.copyOf()
        }
        private fun push(buffer:ByteBuffer){
            val rawReady=delay.push(original,delayed)
            val wetReady=wetStarted && compressor.push(input,transition.pre,transition.post,compressed) && limiter.push(compressed,limited)
            if(!rawReady)return
            if(!wetReady)check(delayed.last()==0f)
            for(c in input.indices)buffer.putShort((delayed[c]*32768).roundToInt().coerceIn(-32768,32767).toShort())
            for(c in input.indices)buffer.putShort(((if(wetReady && delayed.last()>0)limited[c]else delayed[c])*32768).roundToInt().coerceIn(-32768,32767).toShort())
            val control=(delayed.last()*16384).roundToInt().coerceIn(0,16384).toShort()
            buffer.putShort(control);buffer.putShort((-control).toShort());framesOut++
        }
        override fun queueInput(buffer:ByteBuffer){
            if(!buffer.hasRemaining())return
            val next=if(enabled)profiles[selectedKey]else null
            if(next!=lastProfile || seenBoundary!=boundary || seenPlaybackRequest!=playbackRequest){
                transition.choose(next,seenBoundary!=boundary || seenPlaybackRequest!=playbackRequest)
                lastProfile=next;seenBoundary=boundary;seenPlaybackRequest=playbackRequest;profile=next
                inputEntry?.let{selected?.invoke(it)}
            }
            if(next!=null || transition.mix>0)wetStarted=true
            val output=replaceOutputBuffer(buffer.remaining()/inputAudioFormat.bytesPerFrame*outputAudioFormat.bytesPerFrame)
            while(buffer.hasRemaining()){
                for(c in input.indices){input[c]=buffer.short/32768f;original[c]=input[c]}
                original[input.size]=transition.mix.toFloat();framesIn++;push(output);transition.advance(speed())
            }
            output.flip()
        }
        override fun onQueueEndOfStream(){
            if(drained)return
            drained=true
            val output=replaceOutputBuffer(((framesIn-framesOut)*outputAudioFormat.bytesPerFrame).toInt())
            input.fill(0f);original.fill(0f)
            repeat(delay.frames){push(output)}
            check(framesIn==framesOut){"Input frame mismatch: $framesIn/$framesOut"};output.flip()
        }
    }

    internal inner class OutputProcessor:BaseAudioProcessor() {
        private lateinit var limiter:SteadyOversampledLimiter
        private lateinit var delay:SteadyFrameDelay
        private var input=FloatArray(0)
        private var limited=FloatArray(0)
        private var original=FloatArray(0)
        private var delayed=FloatArray(0)
        private var framesIn=0L
        private var framesOut=0L
        private var drained=false
        private var lastStage=-1
        private var wetStarted=false
        override fun onConfigure(format:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
            if(format.encoding!=C.ENCODING_PCM_16BIT || format.channelCount<4 || format.channelCount%2!=0)throw AudioProcessor.UnhandledAudioFormatException(format)
            return AudioProcessor.AudioFormat(format.sampleRate,(format.channelCount-2)/2,format.encoding)
        }
        override fun onFlush(){
            limiter=SteadyOversampledLimiter(inputAudioFormat.sampleRate,outputAudioFormat.channelCount)
            delay=SteadyFrameDelay(outputAudioFormat.channelCount+1,limiter.tailFrames)
            framesIn=0;framesOut=0;drained=false;lastStage=-1;wetStarted=false
            input=FloatArray(outputAudioFormat.channelCount);limited=input.copyOf()
            original=FloatArray(input.size+1);delayed=original.copyOf()
        }
        private fun push(buffer:ByteBuffer){
            if(original.last()>0)wetStarted=true
            val rawReady=delay.push(original,delayed);val wetReady=wetStarted && limiter.push(input,limited)
            if(!rawReady)return
            if(!wetReady)check(delayed.last()==0f)
            val amount=delayed.last().toDouble().coerceIn(0.0,1.0);mix=amount
            for(c in input.indices){
                val value=delayed[c]*(1-amount)+limited[c]*amount
                buffer.putShort((value*32768).roundToInt().coerceIn(-32768,32767).toShort())
            }
            val stage=if(amount<=0)0 else if(amount>=1)2 else 1
            if(stage!=lastStage){lastStage=stage;inputEntry?.let{selected?.invoke(it)}}
            framesOut++
        }
        private fun observe(buffer:ByteBuffer){buffer.flip();outputObserver?.invoke(buffer.asReadOnlyBuffer())}
        override fun queueInput(buffer:ByteBuffer){
            if(!buffer.hasRemaining())return
            val output=replaceOutputBuffer(buffer.remaining()/inputAudioFormat.bytesPerFrame*outputAudioFormat.bytesPerFrame)
            while(buffer.hasRemaining()){
                for(c in input.indices)original[c]=buffer.short/32768f
                for(c in input.indices)input[c]=buffer.short/32768f
                val positive=buffer.short;val negative=buffer.short
                original[input.size]=((positive-negative)/32768f).coerceIn(0f,1f)
                framesIn++;push(output)
            }
            observe(output)
        }
        override fun onQueueEndOfStream(){
            if(drained)return
            drained=true
            val output=replaceOutputBuffer(((framesIn-framesOut)*outputAudioFormat.bytesPerFrame).toInt())
            input.fill(0f);original.fill(0f)
            repeat(delay.frames){push(output)}
            check(framesIn==framesOut){"Output frame mismatch: $framesIn/$framesOut"};observe(output)
        }
    }
}
