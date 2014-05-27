package aQute.lib.persistentmap;

import java.io.*;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.util.*;

import aQute.lib.io.*;
import aQute.lib.json.*;

/**
 * Implements a low performance but easy to use map that is backed on a
 * directory. All objects are stored as JSON objects and therefore should be
 * DTOs. Each key is a file name and the contents is the value encoded in JSON.
 * The PersistentMap will attempt to lock the directory. This is a
 * non-concurrent implementation so you must ensure it is only used in a single
 * thread. It cannot of course also not share the data directory.
 */
public class PersistentMap<V> extends AbstractMap<String,V> implements Closeable {

	final static JSONCodec				codec	= new JSONCodec();
	final File							dir;
	final File							data;
	final RandomAccessFile				lockFile;
	final Map<String,SoftReference<V>>	cache	= new HashMap<String,SoftReference<V>>();
	boolean								inited	= false;
	boolean								closed	= false;

	Type								type;

	public PersistentMap(File dir, Type type) throws IOException {
		this.dir = dir;
		this.type = type;
		dir.mkdirs();
		if (!dir.isDirectory())
			throw new IllegalArgumentException("PersistentMap cannot create directory " + dir);

		if (!dir.canWrite())
			throw new IllegalArgumentException("PersistentMap cannot write directory " + dir);

		File f = new File(dir, "lock");
		lockFile = new RandomAccessFile(f, "rw");

		FileLock lock = lock();
		try {
			data = new File(dir, "data").getAbsoluteFile();
			data.mkdir();
			if (!dir.isDirectory())
				throw new IllegalArgumentException("PersistentMap cannot create data directory " + dir);

			if (!data.canWrite())
				throw new IllegalArgumentException("PersistentMap cannot write data directory " + data);
		}
		finally {
			unlock(lock);
		}

	}

	public PersistentMap(File dir, Class<V> type) throws IOException {
		this(dir, (Type) type);
	}

	public PersistentMap(File dir, Class<V> type, Map<String,V> map) throws IOException {
		this(dir, (Type) type);
		putAll(map);
	}

	public PersistentMap(File dir, Type type, Map<String,V> map) throws IOException {
		this(dir, type);
		putAll(map);
	}

	void init() {
		if (inited)
			return;

		if (closed)
			throw new IllegalStateException("PersistentMap " + dir + " is already closed");

		try {
			inited = true;
			FileLock lock = lock();
			try {
				for (File file : data.listFiles()) {
					cache.put(file.getName(), null);
				}
			}
			finally {
				unlock(lock);
			}
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Set<java.util.Map.Entry<String,V>> entrySet() {
		return new AbstractSet<Map.Entry<String,V>>() {

			public int size() {
				init();
				return cache.size();
			}

			public Iterator<java.util.Map.Entry<String,V>> iterator() {
				init();
				return new Iterator<Map.Entry<String,V>>() {
					Iterator<java.util.Map.Entry<String,SoftReference<V>>>	it	= cache.entrySet().iterator();
					java.util.Map.Entry<String,SoftReference<V>>			entry;

					public boolean hasNext() {
						return it.hasNext();
					}

					@SuppressWarnings("unchecked")
					public java.util.Map.Entry<String,V> next() {
						try {
							entry = it.next();
							SoftReference<V> ref = entry.getValue();
							V value = null;
							if (ref != null)
								value = ref.get();

							if (value == null) {
								File file = new File(data, entry.getKey());
								value = (V) codec.dec().from(file).get(type);
								entry.setValue(new SoftReference<V>(value));
							}

							final V v = value;

							return new Map.Entry<String,V>() {

								public String getKey() {
									return entry.getKey();
								}

								public V getValue() {
									return v;
								}

								public V setValue(V value) {
									return put(entry.getKey(), value);
								}
							};
						}
						catch (Exception e) {
							e.printStackTrace();
							throw new RuntimeException(e);
						}
					}

					public void remove() {
						PersistentMap.this.remove(entry.getKey());
					}
				};
			}
		};
	}

	public V put(String key, V value) {
		init();
		try {
			V old = null;
			SoftReference<V> ref = cache.get(key);
			if (ref != null)
				old = ref.get();

			FileLock lock = lock();
			try {
				File file = new File(data, key);
				codec.enc().to(file).put(value);
				cache.put(key, new SoftReference<V>(value));
				return old;
			}
			finally {
				unlock(lock);
			}
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private FileLock lock() throws IOException {
		System.out.println("locking " + Thread.currentThread().getName());
		FileLock lock = lockFile.getChannel().lock();
		if ( !lock.isValid()) {
			System.out.println("Ouch, got invalid lock " + dir + " " + Thread.currentThread().getName());
			return null;
		}		
		
		return lock;
	}
	private void unlock(FileLock lock) throws IOException {
		if ( lock == null || !lock.isValid()) {
			System.out.println("Ouch, invalid lock was used " + dir+ " " + Thread.currentThread().getName());
			return;
		}
		lock.release();
	}


	public V remove(String key) {
		try {
			init();
			FileLock lock = lock();
			try {
				File file = new File(data, key);
				file.delete();
				if (file.exists())
					throw new IllegalStateException("PersistentMap cannot delete entry " + file);

				return cache.remove(key).get();
			}
			finally {
				unlock(lock);
			}
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void clear() {
		init();
		try {
			FileLock lock = lock();
			try {
				IO.deleteWithException(data);
				cache.clear();
				data.mkdir();
			}
			finally {
				unlock(lock);
			}
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Set<String> keySet() {
		init();
		return cache.keySet();
	}

	public void close() throws IOException {
		lockFile.close();
		closed = true;
		inited = false;
	}

	public String toString() {
		return "PersistentMap[" + dir + "] " + super.toString();
	}

}