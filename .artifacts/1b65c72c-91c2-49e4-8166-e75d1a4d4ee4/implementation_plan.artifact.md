# Fix NullPointerException in FtpServerStore

The application crashes with a `NullPointerException` when initializing `FtpServerStore`. This is because the `init` block calls `ensureLoaded()`, which uses `loadLock`, but `loadLock` is defined *after* the `init` block and thus is not yet initialized when `ensureLoaded()` is executed.

I will also proactively fix `SmbServerStore` and `WebdavServerStore` which are missing the `init` block entirely, which would cause their `servers` flow to be empty until a manual operation is performed.

## Proposed Changes

### [ftp]

#### [MODIFY] [FtpServerStore.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/ftp/src/main/java/com/shelf/reader/ftp/data/FtpServerStore.kt)
- Reorder the `init` block and property declarations (`loaded`, `loadLock`) to ensure properties are initialized before they are used in `init`.

### [smb]

#### [MODIFY] [SmbServerStore.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/smb/src/main/java/com/shelf/reader/smb/data/SmbServerStore.kt)
- Add an `init` block calling `ensureLoaded()` to ensure the saved servers are loaded when the store is created.

### [webdav]

#### [MODIFY] [WebdavServerStore.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/webdav/src/main/java/com/shelf/reader/webdav/data/WebdavServerStore.kt)
- Add an `init` block calling `ensureLoaded()` to ensure the saved servers are loaded when the store is created.

## Verification Plan

### Manual Verification
- Deploy the app and ensure it no longer crashes on startup or when accessing FTP features.
- Verify that saved servers are correctly displayed in FTP, SMB, and WebDAV sections immediately upon opening them.
