package to.trel.internal

import to.trel.Breadcrumb
import java.util.ArrayDeque

internal class User(val id: String?, val email: String?, val name: String?) {
    fun applyTo(attrs: MutableMap<String, Any?>) {
        id?.let { attrs[Attr.USER_ID] = it }
        email?.let { attrs[Attr.USER_EMAIL] = it }
        name?.let { attrs[Attr.USER_NAME] = it }
    }

    companion object {
        val EMPTY = User(null, null, null)
    }
}

/** Per-process mutable context: user, tags, breadcrumb ring buffer. Thread-safe. */
internal class Scope(private val maxBreadcrumbs: Int) {
    private val lock = Any()
    private val crumbs = ArrayDeque<Breadcrumb>(maxBreadcrumbs)
    private val tagMap = LinkedHashMap<String, String>()
    private val contexts = LinkedHashMap<String, String>()
    private val runtime = LinkedHashMap<String, Any?>()

    @Volatile
    var screenName: String? = null

    @Volatile
    var user: User = User.EMPTY
        private set

    val tags: Map<String, String>
        get() = synchronized(lock) { LinkedHashMap(tagMap) }

    fun setUser(id: String?, email: String?, name: String?) {
        user = if (id == null && email == null && name == null) User.EMPTY else User(id, email, name)
    }

    fun setTag(key: String, value: String?) {
        val k = key.trim().take(64)
        if (k.isEmpty()) return
        synchronized(lock) {
            if (value == null) {
                tagMap.remove(k)
            } else if (tagMap.size < MAX_TAGS || tagMap.containsKey(k)) {
                tagMap[k] = value.take(200)
            }
            Unit
        }
    }

    fun setContext(name: String, json: String?) {
        val key = name.trim().take(64)
        if (key.isEmpty()) return
        synchronized(lock) {
            if (json == null) contexts.remove(key) else contexts[key] = json.take(8_000)
        }
    }

    fun setRuntime(values: Map<String, Any?>) {
        synchronized(lock) {
            runtime.clear()
            runtime.putAll(values)
        }
    }

    fun apply(attrs: MutableMap<String, Any?>) {
        synchronized(lock) {
            screenName?.let { attrs[Attr.SCREEN_NAME] = it }
            contexts.forEach { (k, v) -> attrs[Attr.CONTEXT_PREFIX + k] = v }
            runtime.forEach { (k, v) -> if (v != null) attrs[k] = v }
        }
    }

    fun addBreadcrumb(b: Breadcrumb) {
        synchronized(lock) {
            while (crumbs.size >= maxBreadcrumbs) crumbs.pollFirst()
            crumbs.addLast(b)
        }
    }

    fun breadcrumbs(): List<Breadcrumb> = synchronized(lock) { crumbs.toList() }

    private companion object {
        const val MAX_TAGS = 20
    }
}
