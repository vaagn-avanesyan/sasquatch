/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.crashes;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.AppCenterHandler;
import com.microsoft.appcenter.Constants;
import com.microsoft.appcenter.channel.Channel;
import com.microsoft.appcenter.crashes.ingestion.models.ErrorAttachmentLog;
import com.microsoft.appcenter.crashes.ingestion.models.HandledErrorLog;
import com.microsoft.appcenter.crashes.ingestion.models.ManagedErrorLog;
import com.microsoft.appcenter.crashes.ingestion.models.StackFrame;
import com.microsoft.appcenter.crashes.ingestion.models.json.ErrorAttachmentLogFactory;
import com.microsoft.appcenter.crashes.ingestion.models.json.HandledErrorLogFactory;
import com.microsoft.appcenter.crashes.ingestion.models.json.ManagedErrorLogFactory;
import com.microsoft.appcenter.crashes.model.ErrorReport;
import com.microsoft.appcenter.crashes.model.NativeException;
import com.microsoft.appcenter.crashes.model.TestCrashException;
import com.microsoft.appcenter.crashes.utils.ErrorLogHelper;
import com.microsoft.appcenter.ingestion.Ingestion;
import com.microsoft.appcenter.ingestion.models.Device;
import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.ingestion.models.json.DefaultLogSerializer;
import com.microsoft.appcenter.ingestion.models.json.LogFactory;
import com.microsoft.appcenter.ingestion.models.json.LogSerializer;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.DeviceInfoHelper;
import com.microsoft.appcenter.utils.HandlerUtils;
import com.microsoft.appcenter.utils.PrefStorageConstants;
import com.microsoft.appcenter.utils.async.AppCenterConsumer;
import com.microsoft.appcenter.utils.async.AppCenterFuture;
import com.microsoft.appcenter.utils.context.SessionContext;
import com.microsoft.appcenter.utils.context.UserIdContext;
import com.microsoft.appcenter.utils.storage.FileManager;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.json.JSONException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.appcenter.Flags.CRITICAL;
import static com.microsoft.appcenter.Flags.DEFAULTS;
import static com.microsoft.appcenter.crashes.ingestion.models.ErrorAttachmentLog.attachmentWithBinary;
import static com.microsoft.appcenter.test.TestUtils.generateString;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyByte;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyNoMoreInteractions;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@SuppressWarnings("unused")
@PrepareForTest({ErrorLogHelper.class, SystemClock.class, FileManager.class, SharedPreferencesManager.class, AppCenterLog.class, AppCenter.class, Crashes.class, HandlerUtils.class, Looper.class, ErrorAttachmentLog.class})
public class CrashesTest {

    private static final Exception EXCEPTION = new Exception("This is a test exception.");

    private static final String CRASHES_ENABLED_KEY = PrefStorageConstants.KEY_ENABLED + "_" + Crashes.getInstance().getServiceName();

    @Rule
    public final TemporaryFolder errorStorageDirectory = new TemporaryFolder();

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    private ManagedErrorLog mErrorLog;

    @Mock
    private AppCenter mAppCenter;

    @Mock
    private AppCenterHandler mAppCenterHandler;

    private static void assertErrorEquals(ManagedErrorLog errorLog, ErrorReport report) {
        assertNotNull(report);
        assertEquals(errorLog.getId().toString(), report.getId());
        assertEquals(errorLog.getErrorThreadName(), report.getThreadName());
        assertEquals(CrashesTest.EXCEPTION, report.getThrowable());
        assertEquals(errorLog.getAppLaunchTimestamp(), report.getAppStartTime());
        assertEquals(errorLog.getTimestamp(), report.getAppErrorTime());
        assertEquals(errorLog.getDevice(), report.getDevice());
        assertNotNull(errorLog.getDevice());
    }

    @Before
    public void setUp() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        Crashes.unsetInstance();
        mockStatic(SystemClock.class);
        mockStatic(FileManager.class);
        mockStatic(SharedPreferencesManager.class);
        mockStatic(AppCenterLog.class);
        when(SystemClock.elapsedRealtime()).thenReturn(System.currentTimeMillis());
        mockStatic(AppCenter.class);
        when(AppCenter.getInstance()).thenReturn(mAppCenter);

        @SuppressWarnings("unchecked")
        AppCenterFuture<Boolean> future = (AppCenterFuture<Boolean>) mock(AppCenterFuture.class);
        when(AppCenter.isEnabled()).thenReturn(future);
        when(future.get()).thenReturn(true);

        when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(true);

        /* Then simulate further changes to state. */
        doAnswer(new Answer<Object>() {

            @Override
            public Object answer(InvocationOnMock invocation) {

                /* Whenever the new state is persisted, make further calls return the new state. */
                boolean enabled = (Boolean) invocation.getArguments()[1];
                when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(enabled);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putBoolean(eq(CRASHES_ENABLED_KEY), anyBoolean());

        /* Mock handlers. */
        mockStatic(HandlerUtils.class);
        Answer<Void> runNow = new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                ((Runnable) invocation.getArguments()[0]).run();
                return null;
            }
        };
        doAnswer(runNow).when(HandlerUtils.class);
        HandlerUtils.runOnUiThread(any(Runnable.class));
        doAnswer(runNow).when(mAppCenterHandler).post(any(Runnable.class), any(Runnable.class));

        mErrorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);
    }

    @After
    public void tearDown() {
        UserIdContext.unsetInstance();
    }

    @Test
    public void singleton() {
        Assert.assertSame(Crashes.getInstance(), Crashes.getInstance());
    }

    @Test
    public void initializeWhenDisabled() {

        /* Setup mock. */
        Crashes crashes = Crashes.getInstance();
        mockStatic(ErrorLogHelper.class);
        File dir = mock(File.class);
        File file1 = mock(File.class);
        File file2 = mock(File.class);
        UncaughtExceptionHandler mockHandler = mock(UncaughtExceptionHandler.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(dir);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(dir.listFiles()).thenReturn(new File[]{file1, file2});
        crashes.setUncaughtExceptionHandler(mockHandler);
        when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(false);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        /* Test. */
        verifyStatic(times(3));
        SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true);
        assertFalse(Crashes.isEnabled().get());
        assertEquals(crashes.getInitializeTimestamp(), -1);
        assertNull(crashes.getUncaughtExceptionHandler());
        verify(mockHandler).unregister();
    }

    @Test
    public void notInit() {

        /* Just check log is discarded without throwing any exception. */
        Crashes.notifyUserConfirmation(Crashes.SEND);
        Crashes.trackException(EXCEPTION);
        Crashes.trackException(EXCEPTION, new HashMap<String, String>());
        verifyStatic(times(3));
        AppCenterLog.error(eq(AppCenter.LOG_TAG), anyString());
    }

    @Test
    public void checkConfig() {
        Crashes instance = Crashes.getInstance();
        Map<String, LogFactory> factories = instance.getLogFactories();
        assertNotNull(factories);
        assertTrue(factories.remove(ManagedErrorLog.TYPE) instanceof ManagedErrorLogFactory);
        assertTrue(factories.remove(HandledErrorLog.TYPE) instanceof HandledErrorLogFactory);
        assertTrue(factories.remove(ErrorAttachmentLog.TYPE) instanceof ErrorAttachmentLogFactory);
        assertTrue(factories.isEmpty());
        assertEquals(1, instance.getTriggerCount());
        assertEquals(Crashes.ERROR_GROUP, instance.getGroupName());
    }

    @Test
    public void setEnabled() {

        /* Setup mock. */
        Crashes crashes = Crashes.getInstance();
        mockStatic(ErrorLogHelper.class);
        Channel mockChannel = mock(Channel.class);
        File dir = mock(File.class);
        File file1 = mock(File.class);
        File file2 = mock(File.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(dir);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(dir.listFiles()).thenReturn(new File[]{file1, file2});

        /* Before start it's disabled. */
        assertFalse(Crashes.isEnabled().get());
        assertEquals(0, crashes.getInitializeTimestamp());

        /* Start. */
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mockChannel, "", null, true);
        verify(mockChannel).removeGroup(eq(crashes.getGroupName()));
        verify(mockChannel).addGroup(eq(crashes.getGroupName()), anyInt(), anyInt(), anyInt(), isNull(Ingestion.class), any(Channel.GroupListener.class));

        /* Test. */
        assertTrue(Crashes.isEnabled().get());
        Crashes.setEnabled(true);
        assertTrue(Crashes.isEnabled().get());
        assertTrue(crashes.getInitializeTimestamp() > 0);
        Crashes.setEnabled(false);
        assertFalse(Crashes.isEnabled().get());
        verify(mockChannel).clear(crashes.getGroupName());
        verify(mockChannel, times(2)).removeGroup(eq(crashes.getGroupName()));
        assertEquals(crashes.getInitializeTimestamp(), -1);
        assertFalse(Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtExceptionHandler);
        assertFalse(verify(file1).delete());
        assertFalse(verify(file2).delete());
        Crashes.trackException(EXCEPTION);
        verifyNoMoreInteractions(mockChannel);

        /* Enable back, testing double calls. */
        Crashes.setEnabled(true);
        assertTrue(Crashes.isEnabled().get());
        assertTrue(crashes.getInitializeTimestamp() > 0);
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtExceptionHandler);
        Crashes.setEnabled(true);
        assertTrue(Crashes.isEnabled().get());
        verify(mockChannel, times(2)).addGroup(eq(crashes.getGroupName()), anyInt(), anyInt(), anyInt(), isNull(Ingestion.class), any(Channel.GroupListener.class));
        Crashes.trackException(EXCEPTION);
        verify(mockChannel, times(1)).enqueue(isA(HandledErrorLog.class), eq(crashes.getGroupName()), eq(DEFAULTS));
    }

    @Test
    public void setEnabledWithoutContext() {
        Crashes crashes = Crashes.getInstance();
        crashes.setUncaughtExceptionHandler(null);
        crashes.setInstanceEnabled(true);
        assertNull(crashes.getUncaughtExceptionHandler());

        UncaughtExceptionHandler mockHandler = mock(UncaughtExceptionHandler.class);
        crashes.setUncaughtExceptionHandler(mockHandler);
        crashes.setInstanceEnabled(true);
        assertEquals(mockHandler, crashes.getUncaughtExceptionHandler());

        verifyNoMoreInteractions(mockHandler);
    }

    @Test
    public void queuePendingCrashesShouldProcess() throws IOException, ClassNotFoundException, JSONException {

        /* Setup mock. */
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);
        ErrorReport report = new ErrorReport();
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(new RuntimeException());
        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(report)).thenReturn(true);
        when(mockListener.shouldAwaitUserConfirmation()).thenReturn(false);
        ErrorAttachmentLog mockAttachment = mock(ErrorAttachmentLog.class);
        when(mockAttachment.getId()).thenReturn(UUID.randomUUID());
        when(mockAttachment.getErrorId()).thenReturn(UUID.randomUUID());
        when(mockAttachment.getContentType()).thenReturn("");
        when(mockAttachment.getFileName()).thenReturn("");
        when(mockAttachment.getData()).thenReturn(new byte[0]);
        when(mockAttachment.isValid()).thenReturn(true);
        ErrorAttachmentLog mockEmptyAttachment = mock(ErrorAttachmentLog.class);
        final int skipAttachmentLogsCount = 2;
        List<ErrorAttachmentLog> errorAttachmentLogList = Arrays.asList(mockAttachment, mockAttachment, mockEmptyAttachment, null);
        when(mockListener.getErrorAttachments(report)).thenReturn(errorAttachmentLogList);
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mErrorLog);
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        /* Test. */
        verify(mockListener).shouldProcess(report);
        verify(mockListener).shouldAwaitUserConfirmation();
        verify(mockListener).getErrorAttachments(report);
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {
            @Override
            public boolean matches(Object log) {
                return log.equals(mErrorLog);
            }
        }), eq(crashes.getGroupName()), eq(CRITICAL));
        verify(mockChannel, times(errorAttachmentLogList.size() - skipAttachmentLogsCount)).enqueue(mockAttachment, crashes.getGroupName(), DEFAULTS);
    }

    @Test
    public void queuePendingCrashesShouldNotProcess() throws IOException, ClassNotFoundException, JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        ErrorReport report = new ErrorReport();

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(new RuntimeException()).thenReturn(new byte[]{});

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(report)).thenReturn(false);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mErrorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        verify(mockListener).shouldProcess(report);
        verify(mockListener, never()).shouldAwaitUserConfirmation();

        verify(mockListener, never()).getErrorAttachments(report);
        verify(mockChannel, never()).enqueue(any(Log.class), eq(crashes.getGroupName()), anyInt());
    }

    @Test
    public void queuePendingCrashesAlwaysSend() throws IOException, ClassNotFoundException, JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        ErrorAttachmentLog mockAttachment = mock(ErrorAttachmentLog.class);
        when(mockAttachment.getId()).thenReturn(UUID.randomUUID());
        when(mockAttachment.getErrorId()).thenReturn(UUID.randomUUID());
        when(mockAttachment.getContentType()).thenReturn("");
        when(mockAttachment.getFileName()).thenReturn("");
        when(mockAttachment.getData()).thenReturn(new byte[0]);
        when(mockAttachment.isValid()).thenReturn(true);
        List<ErrorAttachmentLog> errorAttachmentLogList = Arrays.asList(mockAttachment, mockAttachment);

        ErrorReport report = new ErrorReport();

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(new RuntimeException());
        when(SharedPreferencesManager.getBoolean(eq(Crashes.PREF_KEY_ALWAYS_SEND), anyBoolean())).thenReturn(true);

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(report)).thenReturn(true);

        when(mockListener.shouldProcess(report)).thenReturn(true);
        when(mockListener.shouldAwaitUserConfirmation()).thenReturn(false);

        when(mockListener.getErrorAttachments(report)).thenReturn(errorAttachmentLogList);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mErrorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        verify(mockListener).shouldProcess(report);
        verify(mockListener, never()).shouldAwaitUserConfirmation();

        verify(mockListener).getErrorAttachments(report);
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {
            @Override
            public boolean matches(Object log) {
                return log.equals(mErrorLog);
            }
        }), eq(crashes.getGroupName()), eq(CRITICAL));

        verify(mockChannel, times(errorAttachmentLogList.size())).enqueue(mockAttachment, crashes.getGroupName(), DEFAULTS);
    }

    @Test
    public void processPendingErrorsCorrupted() throws JSONException {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(FileManager.read(any(File.class))).thenReturn("");

        Crashes crashes = Crashes.getInstance();

        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mock(ManagedErrorLog.class));
        crashes.setLogSerializer(logSerializer);

        CrashesListener listener = mock(CrashesListener.class);
        crashes.setInstanceListener(listener);

        Channel channel = mock(Channel.class);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), channel, "", null, true);
        verifyZeroInteractions(listener);
        verify(channel, never()).enqueue(any(Log.class), anyString(), anyInt());
    }

    @Test
    public void noQueueingWhenDisabled() {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(errorStorageDirectory.getRoot());
        when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(false);
        Channel channel = mock(Channel.class);
        Crashes crashes = Crashes.getInstance();
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), channel, "", null, true);
        verify(channel, never()).enqueue(any(Log.class), anyString(), anyInt());
    }

    @Test
    public void noQueueNullLog() throws JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(null);
        crashes.setLogSerializer(logSerializer);

        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        verify(mockChannel, never()).enqueue(any(Log.class), anyString(), anyInt());
    }

    @Test
    public void printErrorOnJSONException() throws JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);
        JSONException jsonException = new JSONException("Fake JSON exception");

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(FileManager.read(any(File.class))).thenReturn("");
        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);

        when(logSerializer.deserializeLog(anyString(), anyString())).thenThrow(jsonException);
        crashes.setLogSerializer(logSerializer);

        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        verify(mockChannel, never()).enqueue(any(Log.class), anyString(), anyInt());

        verifyStatic();
        AppCenterLog.error(eq(Crashes.LOG_TAG), anyString(), eq(jsonException));
    }

    @Test(expected = TestCrashException.class)
    public void generateTestCrashInDebug() {
        Constants.APPLICATION_DEBUGGABLE = true;
        Crashes.generateTestCrash();
    }

    @Test
    public void generateTestCrashInRelease() {
        Constants.APPLICATION_DEBUGGABLE = false;
        Crashes.generateTestCrash();
    }

    @Test
    public void trackException() {

        /* Track exception test. */
        Crashes crashes = Crashes.getInstance();
        Channel mockChannel = mock(Channel.class);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mockChannel, "", null, true);
        Crashes.trackException(EXCEPTION);
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                return item instanceof HandledErrorLog && EXCEPTION.getMessage().equals(((HandledErrorLog) item).getException().getMessage());
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
        reset(mockChannel);
        Crashes.trackException(EXCEPTION, new HashMap<String, String>() {{
            put(null, null);
            put("", null);
            put(generateString(ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH + 1, '*'), null);
            put("1", null);
        }});
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                return item instanceof HandledErrorLog && EXCEPTION.getMessage().equals(((HandledErrorLog) item).getException().getMessage())
                        && ((HandledErrorLog) item).getProperties().size() == 0;
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
        reset(mockChannel);
        Crashes.trackException(EXCEPTION, new HashMap<String, String>() {{
            for (int i = 0; i < 30; i++) {
                put("valid" + i, "valid");
            }
        }});
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                return item instanceof HandledErrorLog && EXCEPTION.getMessage().equals(((HandledErrorLog) item).getException().getMessage())
                        && ((HandledErrorLog) item).getProperties().size() == 20;
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
        reset(mockChannel);
        final String longerMapItem = generateString(ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH + 1, '*');
        Crashes.trackException(EXCEPTION, new HashMap<String, String>() {{
            put(longerMapItem, longerMapItem);
        }});
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                if (item instanceof HandledErrorLog) {
                    HandledErrorLog errorLog = (HandledErrorLog) item;
                    if (EXCEPTION.getMessage().equals((errorLog.getException().getMessage()))) {
                        if (errorLog.getProperties().size() == 1) {
                            Map.Entry<String, String> entry = errorLog.getProperties().entrySet().iterator().next();
                            String truncatedMapItem = generateString(ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH, '*');
                            return entry.getKey().length() == ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH && entry.getValue().length() == ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH;
                        }
                    }
                }
                return false;
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));

        HandledErrorLog mockLog = mock(HandledErrorLog.class);
        CrashesListener mockListener = mock(CrashesListener.class);
        crashes.setInstanceListener(mockListener);

        /* Crashes callback test for trackException. */
        crashes.getChannelListener().onBeforeSending(mockLog);
        verify(mockListener, never()).onBeforeSending(any(ErrorReport.class));
        crashes.getChannelListener().onSuccess(mockLog);
        verify(mockListener, never()).onSendingSucceeded(any(ErrorReport.class));
        crashes.getChannelListener().onFailure(mockLog, EXCEPTION);
        verify(mockListener, never()).onSendingFailed(any(ErrorReport.class), eq(EXCEPTION));

        ErrorAttachmentLog attachmentLog = mock(ErrorAttachmentLog.class);
        crashes.getChannelListener().onBeforeSending(attachmentLog);
        verify(mockListener, never()).onBeforeSending(any(ErrorReport.class));
        crashes.getChannelListener().onSuccess(attachmentLog);
        verify(mockListener, never()).onSendingSucceeded(any(ErrorReport.class));
        crashes.getChannelListener().onFailure(attachmentLog, EXCEPTION);
        verify(mockListener, never()).onSendingFailed(any(ErrorReport.class), eq(EXCEPTION));
    }

    @Test
    public void trackExceptionForWrapperSdk() {
        StackFrame frame = new StackFrame();
        frame.setClassName("1");
        frame.setFileName("2");
        frame.setLineNumber(3);
        frame.setMethodName("4");
        final com.microsoft.appcenter.crashes.ingestion.models.Exception exception = new com.microsoft.appcenter.crashes.ingestion.models.Exception();
        exception.setType("5");
        exception.setMessage("6");
        exception.setFrames(singletonList(frame));

        Crashes crashes = Crashes.getInstance();
        Channel mockChannel = mock(Channel.class);

        WrapperSdkExceptionManager.trackException(exception);
        verify(mockChannel, never()).enqueue(any(Log.class), eq(crashes.getGroupName()), anyInt());
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mockChannel, "", null, true);
        WrapperSdkExceptionManager.trackException(exception);
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                return item instanceof HandledErrorLog && exception.equals(((HandledErrorLog) item).getException());
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
        reset(mockChannel);
        WrapperSdkExceptionManager.trackException(exception, new HashMap<String, String>() {{
            put(null, null);
            put("", null);
            put(generateString(ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH + 1, '*'), null);
            put("1", null);
        }});
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                return item instanceof HandledErrorLog && exception.equals(((HandledErrorLog) item).getException())
                        && ((HandledErrorLog) item).getProperties().size() == 0;
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
        reset(mockChannel);
        WrapperSdkExceptionManager.trackException(exception, new HashMap<String, String>() {{
            for (int i = 0; i < 30; i++) {
                put("valid" + i, "valid");
            }
        }});
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                return item instanceof HandledErrorLog && exception.equals(((HandledErrorLog) item).getException())
                        && ((HandledErrorLog) item).getProperties().size() == 20;
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
        reset(mockChannel);
        final String longerMapItem = generateString(ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH + 1, '*');
        WrapperSdkExceptionManager.trackException(exception, new HashMap<String, String>() {{
            put(longerMapItem, longerMapItem);
        }});
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object item) {
                if (item instanceof HandledErrorLog) {
                    HandledErrorLog errorLog = (HandledErrorLog) item;
                    if (exception.equals((errorLog.getException()))) {
                        if (errorLog.getProperties().size() == 1) {
                            Map.Entry<String, String> entry = errorLog.getProperties().entrySet().iterator().next();
                            String truncatedMapItem = generateString(ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH, '*');
                            return entry.getKey().length() == ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH && entry.getValue().length() == ErrorLogHelper.MAX_PROPERTY_ITEM_LENGTH;
                        }
                    }
                }
                return false;
            }
        }), eq(crashes.getGroupName()), eq(DEFAULTS));
    }

    @Test
    public void trackExceptionWithUserId() {
        UserIdContext.getInstance().setUserId("charlie");

        /* Track exception test. */
        Crashes crashes = Crashes.getInstance();
        Channel mockChannel = mock(Channel.class);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mockChannel, "", null, true);
        Crashes.trackException(EXCEPTION);
        ArgumentCaptor<HandledErrorLog> log = ArgumentCaptor.forClass(HandledErrorLog.class);
        verify(mockChannel).enqueue(log.capture(), eq(crashes.getGroupName()), eq(DEFAULTS));
        assertNotNull(log.getValue());
        assertEquals(EXCEPTION.getMessage(), log.getValue().getException().getMessage());
        assertEquals("charlie", log.getValue().getUserId());
    }

    @Test
    public void getChannelListener() throws IOException, ClassNotFoundException, JSONException {
        ErrorReport errorReport = ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);

        mockStatic(ErrorLogHelper.class);
        File errorLogFile = mock(File.class);
        when(errorLogFile.length()).thenReturn(1L);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(errorLogFile);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        File throwableFile = mock(File.class);
        when(throwableFile.length()).thenReturn(1L);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(throwableFile);
        when(ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION)).thenReturn(errorReport);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(EXCEPTION);

        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mErrorLog);
        CrashesListener crashesListener = mock(CrashesListener.class);
        when(crashesListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);
        Crashes.setListener(crashesListener);
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        /* The error report was created and cached but device is null here. */
        verifyStatic();
        ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);
        assertNull(errorReport.getDevice());

        /* The channel sets a device. */
        mErrorLog.setDevice(mock(Device.class));

        ArgumentCaptor<ErrorReport> errorReportCaptor = ArgumentCaptor.forClass(ErrorReport.class);
        Channel.GroupListener channelListener = crashes.getChannelListener();

        /* Simulate onBeforeSending event. */
        channelListener.onBeforeSending(mErrorLog);
        verify(crashesListener).onBeforeSending(errorReportCaptor.capture());
        assertErrorEquals(mErrorLog, errorReportCaptor.getValue());

        /* Simulate onSuccess event. */
        channelListener.onSuccess(mErrorLog);
        verify(crashesListener).onSendingSucceeded(errorReportCaptor.capture());
        assertErrorEquals(mErrorLog, errorReportCaptor.getValue());

        /* No more error reports should be produced at the point. */
        verifyStatic();
        ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);

        /* Simulate onFailure event. */
        channelListener.onFailure(mErrorLog, EXCEPTION);
        verify(crashesListener).onSendingFailed(errorReportCaptor.capture(), eq(EXCEPTION));
        assertErrorEquals(mErrorLog, errorReportCaptor.getValue());

        /* onSuccess and onFailure invalidate the cache, so one more call is expected. */
        verifyStatic(times(2));
        ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);
    }

    @Test
    public void getChannelListenerErrors() throws IOException, ClassNotFoundException {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(FileManager.readObject(any(File.class))).thenReturn(null);

        CrashesListener mockListener = mock(CrashesListener.class);
        Crashes crashes = Crashes.getInstance();
        crashes.setInstanceListener(mockListener);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        Channel.GroupListener listener = Crashes.getInstance().getChannelListener();

        listener.onBeforeSending(mErrorLog);
        verifyStatic();
        AppCenterLog.warn(eq(Crashes.LOG_TAG), anyString());
        Mockito.verifyNoMoreInteractions(mockListener);

        listener.onSuccess(mock(Log.class));
        verifyStatic();
        AppCenterLog.warn(eq(Crashes.LOG_TAG), contains(Log.class.getName()));
        Mockito.verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void handleUserConfirmationDoNotSend() throws IOException, ClassNotFoundException, JSONException {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(new ErrorReport());
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(null);

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);
        when(mockListener.shouldAwaitUserConfirmation()).thenReturn(true);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mErrorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        Crashes.notifyUserConfirmation(Crashes.DONT_SEND);

        verify(mockListener, never()).getErrorAttachments(any(ErrorReport.class));

        verifyStatic();
        ErrorLogHelper.removeStoredErrorLogFile(mErrorLog.getId());
        verifyStatic();
        ErrorLogHelper.removeStoredThrowableFile(mErrorLog.getId());
    }

    @Test
    public void handleUserConfirmationAlwaysSend() throws IOException, ClassNotFoundException, JSONException {

        /* Simulate the method is called from Worker Thread. */
        mockStatic(Looper.class);
        when(Looper.myLooper()).thenReturn(mock(Looper.class));
        when(Looper.getMainLooper()).thenReturn(null);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(FileManager.readObject(any(File.class))).thenReturn(null);

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mErrorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        Crashes.notifyUserConfirmation(Crashes.ALWAYS_SEND);

        verifyStatic();
        SharedPreferencesManager.putBoolean(Crashes.PREF_KEY_ALWAYS_SEND, true);
    }

    @Test
    public void buildErrorReport() throws IOException, ClassNotFoundException {
        mErrorLog.setDevice(mock(Device.class));
        ErrorReport errorReport = ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);

        mockStatic(ErrorLogHelper.class);
        File throwableFile = mock(File.class);
        when(throwableFile.length()).thenReturn(1L);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(throwableFile).thenReturn(null);
        when(ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION)).thenReturn(errorReport);
        when(FileManager.readObject(any(File.class))).thenReturn(EXCEPTION);

        Crashes crashes = Crashes.getInstance();
        ErrorReport report = crashes.buildErrorReport(mErrorLog);
        assertErrorEquals(mErrorLog, report);
        verifyStatic();
        ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);

        /* Verify the caching. */
        assertEquals(report, crashes.buildErrorReport(mErrorLog));
        verifyStatic();
        ErrorLogHelper.getErrorReportFromErrorLog(mErrorLog, EXCEPTION);

        mErrorLog.setId(UUID.randomUUID());
        report = crashes.buildErrorReport(mErrorLog);
        assertNull(report);
    }

    @Test
    public void buildErrorReportError() throws IOException, ClassNotFoundException {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        File throwableFile = mock(File.class);
        when(throwableFile.length()).thenReturn(1L);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(throwableFile);
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(mock(ErrorReport.class));

        /* Mock exceptions. */
        Throwable classNotFoundException = mock(ClassNotFoundException.class);
        Throwable ioException = mock(IOException.class);
        Throwable runtimeException = mock(RuntimeException.class);
        Throwable stackOverflowError = mock(StackOverflowError.class);
        when(FileManager.readObject(any(File.class)))
                .thenThrow(classNotFoundException)
                .thenThrow(ioException)
                .thenThrow(runtimeException)
                .thenThrow(stackOverflowError);
        Crashes crashes = Crashes.getInstance();

        /* Test ClassNotFoundException. */
        ErrorReport report = crashes.buildErrorReport(mErrorLog);
        assertNotNull(report);
        verifyStatic();
        AppCenterLog.error(eq(Crashes.LOG_TAG), anyString(), eq(classNotFoundException));

        /* Test IOException. */
        mErrorLog.setId(UUID.randomUUID());
        report = crashes.buildErrorReport(mErrorLog);
        assertNotNull(report);
        verifyStatic();
        AppCenterLog.error(eq(Crashes.LOG_TAG), anyString(), eq(ioException));

        /* Test RuntimeException. */
        mErrorLog.setId(UUID.randomUUID());
        report = crashes.buildErrorReport(mErrorLog);
        assertNotNull(report);
        verifyStatic();
        AppCenterLog.error(eq(Crashes.LOG_TAG), anyString(), eq(runtimeException));

        /* Test StackOverflowError. */
        mErrorLog.setId(UUID.randomUUID());
        report = crashes.buildErrorReport(mErrorLog);
        assertNotNull(report);
        verifyStatic();
        AppCenterLog.error(eq(Crashes.LOG_TAG), anyString(), eq(stackOverflowError));
    }

    @Test
    public void defaultErrorReportingListener() {
        Crashes crashes = Crashes.getInstance();
        CrashesListener defaultListener = crashes.getInstanceListener();
        crashes.setInstanceListener(new CrashesListener() {
            @Override
            public boolean shouldProcess(ErrorReport report) {
                return false;
            }

            @Override
            public boolean shouldAwaitUserConfirmation() {
                return false;
            }

            @Override
            public Iterable<ErrorAttachmentLog> getErrorAttachments(ErrorReport report) {
                return null;
            }

            @Override
            public void onBeforeSending(ErrorReport report) {
            }

            @Override
            public void onSendingFailed(ErrorReport report, Exception e) {
            }

            @Override
            public void onSendingSucceeded(ErrorReport report) {
            }
        });

        /* Verify crashes has default listener when null is assigned. */
        crashes.setInstanceListener(null);
        CrashesListener listener = crashes.getInstanceListener();
        assertEquals(defaultListener, listener);

        /* Verify default behavior. */
        assertTrue(defaultListener.shouldProcess(null));
        assertFalse(defaultListener.shouldAwaitUserConfirmation());

        /* Nothing to verify. */
        defaultListener.getErrorAttachments(null);
        defaultListener.onBeforeSending(null);
        defaultListener.onSendingSucceeded(null);
        defaultListener.onSendingFailed(null, null);
    }

    @Test
    public void noCrashInLastSession() {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(null);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        assertNull(Crashes.getLastSessionCrashReport().get());
        Crashes.getInstance().onStarted(mock(Context.class), mock(Channel.class), "", null, true);
        assertFalse(Crashes.hasCrashedInLastSession().get());
        assertNull(Crashes.getLastSessionCrashReport().get());
        verifyStatic(never());
        AppCenterLog.debug(anyString(), anyString());
    }

    @Test
    public void crashInLastSession() throws JSONException, IOException, ClassNotFoundException {

        final ManagedErrorLog errorLog = new ManagedErrorLog();
        errorLog.setId(UUID.randomUUID());
        errorLog.setErrorThreadName(Thread.currentThread().getName());
        Date logTimestamp = new Date(10);
        errorLog.setTimestamp(logTimestamp);

        Date appLaunchTimestamp = new Date(100L);
        errorLog.setAppLaunchTimestamp(appLaunchTimestamp);
        errorLog.setDevice(mock(Device.class));

        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(errorLog);

        final Throwable throwable = mock(Throwable.class);
        final ErrorReport errorReport = ErrorLogHelper.getErrorReportFromErrorLog(errorLog, throwable);

        mockStatic(ErrorLogHelper.class);
        File lastErrorLogFile = errorStorageDirectory.newFile("last-error-log.json");
        new FileWriter(lastErrorLogFile).append("fake_data").close();
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(lastErrorLogFile);
        File throwableFile = errorStorageDirectory.newFile();
        new FileWriter(throwableFile).append("fake_data").close();
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(throwableFile);
        when(ErrorLogHelper.getErrorReportFromErrorLog(errorLog, throwable)).thenReturn(errorReport);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{lastErrorLogFile});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(FileManager.read(any(File.class))).thenReturn("fake_data");
        when(FileManager.readObject(any(File.class))).thenReturn(throwable);

        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        assertFalse(Crashes.hasCrashedInLastSession().get());

        /*
         * Last session error is only fetched upon initialization: enabled and channel ready.
         * Here the service is disabled by default until stated, we are waiting channel to be ready, simulate that.
         */
        assertFalse(Crashes.isEnabled().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());

        @SuppressWarnings("unchecked")
        AppCenterConsumer<ErrorReport> beforeCallback = (AppCenterConsumer<ErrorReport>) mock(AppCenterConsumer.class);
        Crashes.getLastSessionCrashReport().thenAccept(beforeCallback);
        verify(beforeCallback).accept(null);

        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);
        assertTrue(Crashes.isEnabled().get());
        assertTrue(Crashes.hasCrashedInLastSession().get());

        /* Test with 2 callbacks and check result is the same for both callbacks. */
        @SuppressWarnings("unchecked")
        AppCenterConsumer<ErrorReport> afterCallback = (AppCenterConsumer<ErrorReport>) mock(AppCenterConsumer.class);
        AppCenterFuture<ErrorReport> future = Crashes.getLastSessionCrashReport();
        future.thenAccept(afterCallback);
        future.thenAccept(afterCallback);
        ArgumentCaptor<ErrorReport> errorReportCaptor = ArgumentCaptor.forClass(ErrorReport.class);
        verify(afterCallback, times(2)).accept(errorReportCaptor.capture());
        assertEquals(errorReportCaptor.getAllValues().get(0), errorReportCaptor.getAllValues().get(1));
        ErrorReport result = errorReportCaptor.getValue();
        assertNotNull(result);
        assertEquals(errorLog.getId().toString(), result.getId());
        assertEquals(errorLog.getErrorThreadName(), result.getThreadName());
        assertEquals(appLaunchTimestamp, result.getAppStartTime());
        assertEquals(logTimestamp, result.getAppErrorTime());
        assertNotNull(result.getDevice());
        assertEquals(throwable, result.getThrowable());
    }

    @Test
    public void noCrashInLastSessionWhenDisabled() {

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(errorStorageDirectory.getRoot());

        Crashes.setEnabled(false);

        assertFalse(Crashes.hasCrashedInLastSession().get());
        assertNull(Crashes.getLastSessionCrashReport().get());

        verifyStatic(never());
        ErrorLogHelper.getLastErrorLogFile();
    }

    @Test
    public void failToDeserializeLastSessionCrashReport() throws JSONException, IOException {
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(mock(ManagedErrorLog.class));

        mockStatic(ErrorLogHelper.class);
        File lastErrorLogFile = errorStorageDirectory.newFile("last-error-log.json");
        new FileWriter(lastErrorLogFile).append("fake_data").close();
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(lastErrorLogFile);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{lastErrorLogFile});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(FileManager.read(any(File.class))).thenReturn("fake_data");

        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);

        assertFalse(Crashes.hasCrashedInLastSession().get());

        JSONException jsonException = new JSONException("Fake JSON exception");
        when(logSerializer.deserializeLog(anyString(), anyString())).thenThrow(jsonException);

        /*
         * Last session error is only fetched upon initialization: enabled and channel ready.
         * Here the service is disabled by default until started, we are waiting channel to be ready, simulate that.
         */
        assertFalse(Crashes.isEnabled().get());
        assertNull(Crashes.getLastSessionCrashReport().get());
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);
        assertTrue(Crashes.isEnabled().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());
        assertNull(Crashes.getLastSessionCrashReport().get());

        /*
         * De-serializing fails twice: processing the log from last time as part of the bulk processing.
         * And loading that same file for exposing it in getLastErrorReport.
         */
        verifyStatic(times(2));
        AppCenterLog.error(eq(Crashes.LOG_TAG), anyString(), eq(jsonException));
    }

    @Test
    public void crashInLastSessionCorrupted() throws IOException {
        mockStatic(ErrorLogHelper.class);
        File file = errorStorageDirectory.newFile("last-error-log.json");
        new FileWriter(file).append("fake_data").close();
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{file});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(file);
        Crashes.getInstance().onStarted(mock(Context.class), mock(Channel.class), "", null, true);
        assertFalse(Crashes.hasCrashedInLastSession().get());
        assertNull(Crashes.getLastSessionCrashReport().get());
    }

    @Test
    public void getLastSessionCrashReportWithMultipleListenersAndResultIsNullBeforeInit() {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(null);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);

        @SuppressWarnings("unchecked")
        AppCenterConsumer<ErrorReport> callback = (AppCenterConsumer<ErrorReport>) mock(AppCenterConsumer.class);

        /* Call twice for multiple callbacks before initialize. */
        Crashes.getLastSessionCrashReport().thenAccept(callback);
        Crashes.getLastSessionCrashReport().thenAccept(callback);
        Crashes.getInstance().onStarted(mock(Context.class), mock(Channel.class), "", null, true);
        assertFalse(Crashes.hasCrashedInLastSession().get());
        verify(callback, times(2)).accept(null);
    }

    @Test
    public void sendMoreThan2ErrorAttachments() throws IOException, ClassNotFoundException, JSONException {
        int MAX_ATTACHMENT_PER_CRASH = 2;
        int numOfAttachments = MAX_ATTACHMENT_PER_CRASH + 1;

        ArrayList<ErrorAttachmentLog> errorAttachmentLogs = new ArrayList<>(3);
        for (int i = 0; i < numOfAttachments; ++i) {
            ErrorAttachmentLog log = mock(ErrorAttachmentLog.class);
            when(log.isValid()).thenReturn(true);
            errorAttachmentLogs.add(log);
        }

        CrashesListener listener = mock(CrashesListener.class);
        when(listener.shouldProcess(any(ErrorReport.class))).thenReturn(true);
        when(listener.getErrorAttachments(any(ErrorReport.class))).thenReturn(errorAttachmentLogs);

        ManagedErrorLog log = mock(ManagedErrorLog.class);
        when(log.getId()).thenReturn(UUID.randomUUID());

        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenReturn(log);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(new ErrorReport());

        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(mock(Throwable.class));

        Crashes crashes = Crashes.getInstance();
        crashes.setInstanceListener(listener);
        crashes.setLogSerializer(logSerializer);

        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        String expectedMessage = "A limit of " + MAX_ATTACHMENT_PER_CRASH + " attachments per error report might be enforced by server.";
        PowerMockito.verifyStatic();
        AppCenterLog.warn(Crashes.LOG_TAG, expectedMessage);
    }

    @Test
    public void manualProcessing() throws Exception {

        /* Setup mock for a crash in disk. */
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);
        ErrorReport report1 = new ErrorReport();
        report1.setId(UUID.randomUUID().toString());
        ErrorReport report2 = new ErrorReport();
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class), mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report1).thenReturn(report2);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(new RuntimeException());
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenAnswer(new Answer<ManagedErrorLog>() {

            @Override
            public ManagedErrorLog answer(InvocationOnMock invocation) {
                ManagedErrorLog log = mock(ManagedErrorLog.class);
                when(log.getId()).thenReturn(UUID.randomUUID());
                return log;
            }
        });
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);

        /* Create listener for user confirmation. */
        CrashesListener listener = mock(CrashesListener.class);
        Crashes.setListener(listener);

        /* Set manual processing. */
        WrapperSdkExceptionManager.setAutomaticProcessing(false);

        /* Start crashes. */
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        /* No log queued. */
        verify(mockChannel, never()).enqueue(any(Log.class), eq(crashes.getGroupName()), anyInt());

        /* Get crash reports. */
        Collection<ErrorReport> reports = WrapperSdkExceptionManager.getUnprocessedErrorReports().get();
        assertNotNull(reports);
        assertEquals(2, reports.size());
        Iterator<ErrorReport> iterator = reports.iterator();
        assertEquals(report1, iterator.next());
        assertEquals(report2, iterator.next());

        /* Listener not called yet on anything on manual processing. */
        verifyZeroInteractions(listener);

        /* Send only the first. */
        assertFalse(WrapperSdkExceptionManager.sendCrashReportsOrAwaitUserConfirmation(Collections.singletonList(report1.getId())).get());

        /* We used manual process function, listener not called. */
        verifyZeroInteractions(listener);

        /* No log sent until manual user confirmation in that mode (we are not in always send). */
        verify(mockChannel, never()).enqueue(any(ManagedErrorLog.class), eq(crashes.getGroupName()), anyInt());

        /* Confirm with always send. */
        Crashes.notifyUserConfirmation(Crashes.ALWAYS_SEND);
        verifyStatic();
        SharedPreferencesManager.putBoolean(Crashes.PREF_KEY_ALWAYS_SEND, true);
        when(SharedPreferencesManager.getBoolean(eq(Crashes.PREF_KEY_ALWAYS_SEND), anyBoolean())).thenReturn(true);

        /* 1 log sent. Other one is filtered. */
        verify(mockChannel).enqueue(any(ManagedErrorLog.class), eq(crashes.getGroupName()), eq(CRITICAL));

        /* We can send attachments via wrapper instead of using listener (both work but irrelevant to test with listener). */
        ErrorAttachmentLog mockAttachment = mock(ErrorAttachmentLog.class);
        when(mockAttachment.getId()).thenReturn(UUID.randomUUID());
        when(mockAttachment.getData()).thenReturn(new byte[0]);
        when(mockAttachment.isValid()).thenReturn(true);
        WrapperSdkExceptionManager.sendErrorAttachments(report1.getId(), Collections.singletonList(mockAttachment));
        verify(mockChannel).enqueue(eq(mockAttachment), eq(crashes.getGroupName()), eq(DEFAULTS));

        /* Send attachment with invalid UUID format for report identifier. */
        mockAttachment = mock(ErrorAttachmentLog.class);
        when(mockAttachment.getId()).thenReturn(UUID.randomUUID());
        when(mockAttachment.getData()).thenReturn(new byte[0]);
        when(mockAttachment.isValid()).thenReturn(true);
        WrapperSdkExceptionManager.sendErrorAttachments("not-a-uuid", Collections.singletonList(mockAttachment));
        verify(mockChannel, never()).enqueue(eq(mockAttachment), eq(crashes.getGroupName()), anyInt());

        /* We used manual process function, listener not called and our mock channel does not send events. */
        verifyZeroInteractions(listener);

        /* Reset instance to test another tine with always send. */
        Crashes.unsetInstance();
        crashes = Crashes.getInstance();
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report1).thenReturn(report2);
        WrapperSdkExceptionManager.setAutomaticProcessing(false);
        crashes.setLogSerializer(logSerializer);
        crashes.onStarting(mAppCenterHandler);
        mockChannel = mock(Channel.class);
        crashes.onStarted(mockContext, mockChannel, "", null, true);
        assertTrue(Crashes.isEnabled().get());
        verify(mockChannel, never()).enqueue(any(ManagedErrorLog.class), eq(crashes.getGroupName()), anyInt());

        /* Get crash reports, check always sent was returned and sent without confirmation. */
        assertTrue(WrapperSdkExceptionManager.sendCrashReportsOrAwaitUserConfirmation(Collections.singletonList(report2.getId())).get());
        verify(mockChannel).enqueue(any(ManagedErrorLog.class), eq(crashes.getGroupName()), eq(CRITICAL));
    }

    @Test
    public void manuallyReportNullList() throws Exception {

        /* Setup mock for a crash in disk. */
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);
        ErrorReport report1 = new ErrorReport();
        report1.setId(UUID.randomUUID().toString());
        ErrorReport report2 = new ErrorReport();
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class), mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report1).thenReturn(report2);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(new RuntimeException());
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenAnswer(new Answer<ManagedErrorLog>() {

            @Override
            public ManagedErrorLog answer(InvocationOnMock invocation) {
                ManagedErrorLog log = mock(ManagedErrorLog.class);
                when(log.getId()).thenReturn(UUID.randomUUID());
                return log;
            }
        });
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);

        /* Set manual processing. */
        WrapperSdkExceptionManager.setAutomaticProcessing(false);

        /* Start crashes. */
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mockContext, mockChannel, "", null, true);

        /* No log queued. */
        verify(mockChannel, never()).enqueue(any(Log.class), eq(crashes.getGroupName()), anyInt());

        /* Get crash reports. */
        Collection<ErrorReport> reports = WrapperSdkExceptionManager.getUnprocessedErrorReports().get();
        assertNotNull(reports);
        assertEquals(2, reports.size());
        Iterator<ErrorReport> iterator = reports.iterator();
        assertEquals(report1, iterator.next());
        assertEquals(report2, iterator.next());

        /* Send nothing using null. */
        assertFalse(WrapperSdkExceptionManager.sendCrashReportsOrAwaitUserConfirmation(null).get());

        /* No log sent. */
        verify(mockChannel, never()).enqueue(any(ManagedErrorLog.class), eq(crashes.getGroupName()), anyInt());
    }

    private ManagedErrorLog testNativeCrashLog(long appStartTime, long crashTime, boolean correlateSession) throws Exception {

        /* Setup mock for a crash in disk. */
        File minidumpFile = mock(File.class);
        when(minidumpFile.getName()).thenReturn("mockFile");
        when(minidumpFile.lastModified()).thenReturn(crashTime);
        mockStatic(SessionContext.class);
        SessionContext sessionContext = mock(SessionContext.class);
        when(SessionContext.getInstance()).thenReturn(sessionContext);
        if (correlateSession) {
            SessionContext.SessionInfo sessionInfo = mock(SessionContext.SessionInfo.class);
            when(sessionContext.getSessionAt(crashTime)).thenReturn(sessionInfo);
            when(sessionInfo.getAppLaunchTimestamp()).thenReturn(appStartTime);
        }
        mockStatic(DeviceInfoHelper.class);
        when(DeviceInfoHelper.getDeviceInfo(any(Context.class))).thenReturn(mock(Device.class));
        ErrorReport report = new ErrorReport();
        mockStatic(ErrorLogHelper.class);
        File errorLogFile = mock(File.class);
        when(errorLogFile.length()).thenReturn(1L);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(errorLogFile);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[]{minidumpFile});
        File pendingDir = mock(File.class);
        Whitebox.setInternalState(pendingDir, "path", "");
        when(ErrorLogHelper.getPendingMinidumpDirectory()).thenReturn(pendingDir);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(FileManager.read(any(File.class))).thenReturn("");
        when(FileManager.readObject(any(File.class))).thenReturn(new NativeException());
        LogSerializer logSerializer = mock(LogSerializer.class);
        ArgumentCaptor<Log> log = ArgumentCaptor.forClass(Log.class);
        when(logSerializer.serializeLog(log.capture())).thenReturn("{}");
        when(logSerializer.deserializeLog(anyString(), anyString())).thenAnswer(new Answer<ManagedErrorLog>() {

            @Override
            public ManagedErrorLog answer(InvocationOnMock invocation) {
                ManagedErrorLog log = mock(ManagedErrorLog.class);
                when(log.getId()).thenReturn(UUID.randomUUID());
                return log;
            }
        });

        /* Start crashes. */
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        /* Verify timestamps on the crash log. */
        assertTrue(Crashes.hasCrashedInLastSession().get());
        assertTrue(log.getValue() instanceof ManagedErrorLog);
        return (ManagedErrorLog) log.getValue();
    }

    @Test
    @PrepareForTest({SessionContext.class, DeviceInfoHelper.class})
    public void minidumpAppLaunchTimestampFromSessionContext() throws Exception {
        long appStartTime = 99L;
        long crashTime = 123L;
        ManagedErrorLog crashLog = testNativeCrashLog(appStartTime, crashTime, true);
        assertEquals(new Date(crashTime), crashLog.getTimestamp());
        assertEquals(new Date(appStartTime), crashLog.getAppLaunchTimestamp());
    }

    @Test
    @PrepareForTest({SessionContext.class, DeviceInfoHelper.class})
    public void minidumpAppLaunchTimestampFromSessionContextInFuture() throws Exception {
        long appStartTime = 101L;
        long crashTime = 100L;
        ManagedErrorLog crashLog = testNativeCrashLog(appStartTime, crashTime, true);

        /* Verify we fall back to crash time for app start time. */
        assertEquals(new Date(crashTime), crashLog.getTimestamp());
        assertEquals(new Date(crashTime), crashLog.getAppLaunchTimestamp());
    }

    @Test
    @PrepareForTest({SessionContext.class, DeviceInfoHelper.class})
    public void minidumpAppLaunchTimestampFromSessionContextNotFound() throws Exception {
        long appStartTime = 99L;
        long crashTime = 123L;
        ManagedErrorLog crashLog = testNativeCrashLog(appStartTime, crashTime, false);

        /* Verify we fall back to crash time for app start time. */
        assertEquals(new Date(crashTime), crashLog.getTimestamp());
        assertEquals(new Date(crashTime), crashLog.getAppLaunchTimestamp());
    }

    @Test
    public void minidumpFilePathNull() throws Exception {

        /* Set up mock for the crash. */
        long appStartTime = 99L;
        long crashTime = 123L;
        final com.microsoft.appcenter.crashes.ingestion.models.Exception exception = mock(com.microsoft.appcenter.crashes.ingestion.models.Exception.class);
        DefaultLogSerializer defaultLogSerializer = mock(DefaultLogSerializer.class);
        mock(ErrorAttachmentLog.class);
        mockStatic(ErrorLogHelper.class);
        mockStatic(ErrorAttachmentLog.class);
        ErrorReport errorReport = new ErrorReport();
        errorReport.setThrowable(new NativeException());
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(errorReport);
        whenNew(DefaultLogSerializer.class).withAnyArguments().thenReturn(defaultLogSerializer);
        whenNew(com.microsoft.appcenter.crashes.ingestion.models.Exception.class).withAnyArguments().thenReturn(exception);
        when(exception.getMinidumpFilePath()).thenReturn(null);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class), mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(FileManager.read(any(File.class))).thenReturn("");
        String jsonCrash = "{}";
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenAnswer(new Answer<ManagedErrorLog>() {

            @Override
            public ManagedErrorLog answer(InvocationOnMock invocation) {
                ManagedErrorLog log = mock(ManagedErrorLog.class);
                when(log.getId()).thenReturn(UUID.randomUUID());
                when(log.getException()).thenReturn(exception);
                return log;
            }
        });
        when(logSerializer.serializeLog(any(Log.class))).thenReturn(jsonCrash);
        when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(true);
        ErrorAttachmentLog errorAttachmentLog = mock(ErrorAttachmentLog.class);
        whenNew(ErrorAttachmentLog.class).withAnyArguments().thenReturn(errorAttachmentLog);

        /* Start crashes. */
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "secret-app-mock", null, true);

        /*
         * Verify that attachmentWithBinary doesn't get called if minidump is missing.
         * This scenario used to crash before, so if the test succeeds that also tests the crash is fixed.
         */
        verifyStatic(never());
        attachmentWithBinary(new byte[]{anyByte()}, anyString(), anyString());
    }

    @Test
    public void minidumpStoredWithOldSDK() throws Exception {

        /* Set up mock for the crash. */
        long appStartTime = 99L;
        long crashTime = 123L;
        final com.microsoft.appcenter.crashes.ingestion.models.Exception exception = mock(com.microsoft.appcenter.crashes.ingestion.models.Exception.class);
        DefaultLogSerializer defaultLogSerializer = mock(DefaultLogSerializer.class);
        mock(ErrorAttachmentLog.class);
        mockStatic(ErrorLogHelper.class);
        mockStatic(ErrorAttachmentLog.class);
        ErrorReport errorReport = new ErrorReport();
        errorReport.setThrowable(new NativeException());
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(errorReport);
        whenNew(DefaultLogSerializer.class).withAnyArguments().thenReturn(defaultLogSerializer);
        whenNew(com.microsoft.appcenter.crashes.ingestion.models.Exception.class).withAnyArguments().thenReturn(exception);
        when(exception.getStackTrace()).thenReturn("some minidump");

        /* This mocks we already processed minidump to convert to pending regular crash report as that would be the case if migrating data from older SDK. */
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{mock(File.class)});
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[0]);
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(mock(File.class));
        when(FileManager.read(any(File.class))).thenReturn("");
        String jsonCrash = "{}";
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString(), anyString())).thenAnswer(new Answer<ManagedErrorLog>() {

            @Override
            public ManagedErrorLog answer(InvocationOnMock invocation) {
                ManagedErrorLog log = mock(ManagedErrorLog.class);
                when(log.getId()).thenReturn(UUID.randomUUID());
                when(log.getException()).thenReturn(exception);
                return log;
            }
        });
        when(logSerializer.serializeLog(any(Log.class))).thenReturn(jsonCrash);
        when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(true);
        ErrorAttachmentLog errorAttachmentLog = mock(ErrorAttachmentLog.class);
        whenNew(ErrorAttachmentLog.class).withAnyArguments().thenReturn(errorAttachmentLog);

        /* Start crashes. */
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "secret-app-mock", null, true);

        /* Verify that attachmentWithBinary does get sent. */
        verifyStatic();
        attachmentWithBinary(new byte[]{anyByte()}, anyString(), anyString());

        /* Verify temporary field erased. */
        verify(exception).setStackTrace(null);
    }

    @Test
    public void stackOverflowOnSavingThrowable() throws Exception {

        /* Mock error log utils. */
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(mock(File.class));
        when(ErrorLogHelper.getNewMinidumpFiles()).thenReturn(new File[]{});
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{});
        when(ErrorLogHelper.createErrorLog(any(Context.class), any(Thread.class), any(com.microsoft.appcenter.crashes.ingestion.models.Exception.class), anyMapOf(Thread.class, StackTraceElement[].class), anyLong(), anyBoolean())).thenReturn(mErrorLog);
        File throwableFile = mock(File.class);
        whenNew(File.class).withParameterTypes(File.class, String.class).withArguments(any(File.class), argThat(new ArgumentMatcher<String>() {

            @Override
            public boolean matches(Object argument) {
                return argument.toString().endsWith(ErrorLogHelper.THROWABLE_FILE_EXTENSION);
            }
        })).thenReturn(throwableFile);
        LogSerializer logSerializer = mock(LogSerializer.class);
        String jsonCrash = "{}";
        when(logSerializer.serializeLog(any(Log.class))).thenReturn(jsonCrash);

        /* Mock storage to fail on stack overflow when saving a Throwable as binary. */
        doThrow(new StackOverflowError()).when(FileManager.class);
        FileManager.writeObject(any(File.class), any(Serializable.class));

        /* Simulate start SDK. */
        Crashes crashes = Crashes.getInstance();
        crashes.setLogSerializer(logSerializer);
        crashes.onStarting(mAppCenterHandler);
        crashes.onStarted(mock(Context.class), mock(Channel.class), "", null, true);

        /* Simulate crash. */
        Throwable throwable = new Throwable();
        Crashes.getInstance().saveUncaughtException(Thread.currentThread(), throwable);

        /* Verify we gracefully abort saving throwable (no exception) and we created an empty file instead. */
        verifyStatic();
        FileManager.writeObject(throwableFile, throwable);
        assertNotNull(throwableFile);
        InOrder inOrder = inOrder(throwableFile);

        //noinspection ResultOfMethodCallIgnored
        inOrder.verify(throwableFile).delete();

        //noinspection ResultOfMethodCallIgnored
        inOrder.verify(throwableFile).createNewFile();

        /* Verify it didn't prevent saving the JSON file. */
        verifyStatic();
        FileManager.write(any(File.class), eq(jsonCrash));
    }
}
