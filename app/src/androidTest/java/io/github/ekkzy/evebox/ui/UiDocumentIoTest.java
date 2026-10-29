package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic local files exercise the same truncate-on-open stream contract as SAF providers. */
@RunWith(AndroidJUnit4.class)
public final class UiDocumentIoTest {
    private UiDocumentIo documents;
    private File file;

    @Before public void prepare()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        file=File.createTempFile("evebox-document-test-",".json",context.getCacheDir());
        main(()->documents=new UiDocumentIo(context));
    }
    @After public void close()throws Exception{
        if(documents!=null)main(()->documents.close());
        if(file!=null)Files.deleteIfExists(file.toPath());
    }

    @Test public void oversizedUtf8ExportCannotTruncateAnExistingDocument()throws Exception{
        String original="{\"preserve\":true}";
        Files.write(file.toPath(),original.getBytes(StandardCharsets.UTF_8));
        // Fits the character limit, but exceeds the encoded byte limit.
        assertFalse(write("я".repeat(32769)));
        assertEquals(original,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }

    @Test public void emptyAndNullExportCannotOpenAnExistingDocument()throws Exception{
        String original="{\"preserve\":true}";
        Files.write(file.toPath(),original.getBytes(StandardCharsets.UTF_8));
        assertFalse(write(""));assertFalse(write(null));
        assertEquals(original,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }

    @Test public void validExportStillReplacesTheDocument()throws Exception{
        Files.write(file.toPath(),"old longer contents".getBytes(StandardCharsets.UTF_8));
        assertTrue(write("{\"ok\":true}"));
        assertEquals("{\"ok\":true}",new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }

    private boolean write(String value)throws Exception{
        CountDownLatch complete=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();
        main(()->documents.write(Uri.fromFile(file),value,(written,text)->{success.set(written);complete.countDown();}));
        assertTrue("Document callback must complete",complete.await(5,TimeUnit.SECONDS));return success.get();
    }
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
}
