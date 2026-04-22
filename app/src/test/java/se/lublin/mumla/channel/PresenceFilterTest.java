package se.lublin.mumla.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import se.lublin.humla.model.IUser;

public class PresenceFilterTest {

    private PresenceCache mCache;

    @Before
    public void setUp() {
        mCache = new PresenceCache(null /* adminUrl unused — we set state directly */);
    }

    /** Inject a literal map into the cache for assertions; bypasses the
     *  HTTP fetch path because we don't want network in unit tests. */
    private void seed(Map<String, String> map) {
        try {
            java.lang.reflect.Field f =
                    PresenceCache.class.getDeclaredField("mStatusByLcUsername");
            f.setAccessible(true);
            f.set(mCache, java.util.Collections.unmodifiableMap(map));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static IUser fakeUser(String name) {
        IUser u = org.mockito.Mockito.mock(IUser.class);
        org.mockito.Mockito.when(u.getName()).thenReturn(name);
        return u;
    }

    @Test
    public void online_user_is_visible() {
        seed(Map.of("alice", "online"));
        assertFalse(PresenceFilter.isHidden(fakeUser("alice"), mCache, "self"));
    }

    @Test
    public void busy_user_is_visible() {
        seed(Map.of("alice", "busy"));
        assertFalse(PresenceFilter.isHidden(fakeUser("alice"), mCache, "self"));
    }

    @Test
    public void offline_user_is_hidden() {
        seed(Map.of("alice", "offline"));
        assertTrue(PresenceFilter.isHidden(fakeUser("alice"), mCache, "self"));
    }

    @Test
    public void null_status_user_is_visible() {
        Map<String, String> m = new HashMap<>();
        m.put("alice", null);
        seed(m);
        assertFalse(PresenceFilter.isHidden(fakeUser("alice"), mCache, "self"));
    }

    @Test
    public void missing_from_map_user_is_visible() {
        seed(Map.of());
        assertFalse(PresenceFilter.isHidden(fakeUser("alice"), mCache, "self"));
    }

    @Test
    public void self_offline_is_still_visible() {
        seed(Map.of("alice", "offline"));
        assertFalse(PresenceFilter.isHidden(fakeUser("alice"), mCache, "ALICE"));
    }

    @Test
    public void countVisible_drops_offline_keeps_busy() {
        seed(Map.of("a", "online", "b", "busy", "c", "offline"));
        List<IUser> users = Arrays.asList(
                fakeUser("a"), fakeUser("b"), fakeUser("c"));
        assertEquals(2, PresenceFilter.countVisible(users, mCache, "self"));
    }

    @Test
    public void countVisible_keeps_self_even_if_offline() {
        seed(Map.of("a", "offline"));
        List<IUser> users = Arrays.asList(fakeUser("a"));
        assertEquals(1, PresenceFilter.countVisible(users, mCache, "a"));
    }

    @Test
    public void countVisible_null_list_returns_zero() {
        seed(Map.of());
        assertEquals(0, PresenceFilter.countVisible(null, mCache, "self"));
    }
}
