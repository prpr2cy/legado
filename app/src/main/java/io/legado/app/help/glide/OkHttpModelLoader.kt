package io.legado.app.help.glide

import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.isAbsUrl
import java.io.InputStream

object OkHttpModelLoader : ModelLoader<GlideUrl?, InputStream?> {

    val loadOnlyWifiOption = Option.memory("loadOnlyWifi", false)
    val sourceOriginOption = Option.memory<String>("sourceOrigin")
    val sourceOption = Option.memory("source", null)

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream?> {
        val cacheKey = model.toString()
        var modelWithHeader = model
        val source = options.get(sourceOriginOption)?.let { sourceUrl ->
            SourceHelp.getSource(sourceUrl)
        }
        if (cacheKey.isAbsUrl()) {
            modelWithHeader = AnalyzeUrl(cacheKey, source = source).getGlideUrl()
        }
        options.set(sourceOption, source)
        return ModelLoader.LoadData(modelWithHeader, OkHttpStreamFetcher(modelWithHeader, options))
    }

    override fun handles(model: GlideUrl): Boolean {
        return true
    }

}