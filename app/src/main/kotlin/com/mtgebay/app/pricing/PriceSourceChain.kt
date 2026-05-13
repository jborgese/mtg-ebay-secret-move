package com.mtgebay.app.pricing

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Tries each [sources] in order, returning the first non-null [PriceResult].
 * IO and serialization failures fall through to the next source so a flaky
 * primary doesn't kill the whole price lookup. Coroutine cancellation
 * propagates as expected.
 */
class PriceSourceChain(
    private val sources: List<PriceSource>,
) : PriceSource {

    override val name: String = "chain"

    override suspend fun query(query: PriceQuery): PriceResult? {
        for (source in sources) {
            try {
                val result = source.query(query)
                if (result != null) return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "${source.name} IO failure; falling through", e)
            } catch (e: Exception) {
                Log.w(TAG, "${source.name} threw; falling through", e)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "PriceSourceChain"
    }
}
