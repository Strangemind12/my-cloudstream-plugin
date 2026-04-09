package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class ExampleProvider : MainAPI() { 
    override var mainUrl = "https://muviex.lovable.app/" 
    override var name = "muviex"
    override val supportedTypes = setOf(TvType.Movie)

    override var lang = "en"

    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }
}
