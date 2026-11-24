package it.firemuseum

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import it.firemuseum.cache.BlobStore
import it.firemuseum.cache.MemoryTileCache
import it.firemuseum.cache.SegmentCache
import it.firemuseum.cache.SpaceGovernor
import it.firemuseum.catalog.CatalogRepository
import it.firemuseum.catalog.Wikidata
import it.firemuseum.net.Net
import it.firemuseum.storage.StorageManager
import it.firemuseum.tiles.SourceFactory
import it.firemuseum.tiles.TileLoader
import it.firemuseum.ui.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Singleton "poveri" ma espliciti: l'app è piccola e su Fire TV gira una sola Activity alla volta.
 * Tutte le risorse pesanti (cache su disco, rete, decoder) vivono qui e vengono ri-agganciate
 * al volume giusto quando il disco USB viene inserito o rimosso.
 */
class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: Settings; private set
    lateinit var storage: StorageManager; private set
    lateinit var net: Net; private set
    lateinit var tiles: SegmentCache; private set
    lateinit var blobs: BlobStore; private set
    lateinit var governor: SpaceGovernor; private set
    lateinit var memory: MemoryTileCache; private set
    lateinit var loader: TileLoader; private set
    lateinit var sources: SourceFactory; private set
    lateinit var catalog: CatalogRepository; private set
    lateinit var wikidata: Wikidata; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        net = Net(settings)
        storage = StorageManager(this, settings)
        tiles = SegmentCache(storage, settings, appScope)
        blobs = BlobStore(storage, settings, net, appScope)
        governor = SpaceGovernor(storage, tiles, blobs, appScope)
        tiles.governor = governor
        blobs.governor = governor
        storage.addListener { tiles.reopen(); blobs.reopen(); memory.clear(); governor.trimAsync() }
        storage.start()
        governor.trimAsync()

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // memoryClass con largeHeap: su Fire TV Stick 4K / 4K Max di solito 256-384 MB.
        val heapMb = am.largeMemoryClass.coerceAtLeast(96)
        val memBytes = (heapMb * 1024L * 1024L * 0.30).toLong().coerceIn(32L shl 20, 160L shl 20)
        memory = MemoryTileCache(memBytes.toInt(), lowRam = am.isLowRamDevice || heapMb < 200)

        loader = TileLoader(this, appScope)
        sources = SourceFactory(this)
        wikidata = Wikidata(this)
        catalog = CatalogRepository(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) memory.trimTo(0.4f)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) memory.trimTo(0.1f)
    }

    companion object {
        lateinit var instance: App; private set
    }
}
