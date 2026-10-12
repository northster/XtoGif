package io.github.northster.xsaver

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import io.github.northster.xsaver.data.Http

class XSaverApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
                add(AnimatedImageDecoder.Factory()) // GIF previews animate (API 28+)
                add(VideoFrameDecoder.Factory())    // thumbnails of gallery videos
            }
            .build()
}
