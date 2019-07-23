/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.ingestion.models.one;

import com.microsoft.appcenter.ingestion.models.Device;
import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.utils.context.UserIdContext;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.microsoft.appcenter.Constants.COMMON_SCHEMA_PREFIX_SEPARATOR;

/**
 * Populate Part A properties.
 */
public class PartAUtils {

    /**
     * Name pattern to validate against.
     */
    private static final Pattern NAME_REGEX = Pattern.compile("^[a-zA-Z0-9]((\\.(?!(\\.|$)))|[_a-zA-Z0-9]){3,99}$");

    /**
     * Get the project identifier from the full target token (aka ingestion key or apiKey).
     *
     * @param targetToken transmission target token.
     * @return the ikey or the original string if format is invalid.
     */
    public static String getTargetKey(String targetToken) {
        return targetToken.split("-")[0];
    }

    /**
     * Validate and set name for common schema log.
     *
     * @param log  log.
     * @param name name.
     * @throws IllegalArgumentException if name is invalid.
     */
    public static void setName(CommonSchemaLog log, String name) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name cannot be null.");
        }
        if (!NAME_REGEX.matcher(name).matches()) {
            throw new IllegalArgumentException("Name must match '" + NAME_REGEX + "' but was '" + name + "'.");
        }
        log.setName(name);
    }

    /**
     * Adds part A extension to common schema log from device object in Log.
     *
     * @param src                source log.
     * @param dest               destination common schema log.
     * @param transmissionTarget transmission target to use.
     */
    public static void addPartAFromLog(Log src, CommonSchemaLog dest, String transmissionTarget) {

        /* TODO: We should cache the extension. */
        Device device = src.getDevice();

        /* Add top level part A fields. */
        dest.setVer("3.0");
        dest.setTimestamp(src.getTimestamp());

        /* TODO: We should cache the ikey for transmission target */
        dest.setIKey("o" + COMMON_SCHEMA_PREFIX_SEPARATOR + getTargetKey(transmissionTarget));

        /* Copy target token also in the set. */
        dest.addTransmissionTarget(transmissionTarget);

        /* Add extension. */
        if (dest.getExt() == null) {
            dest.setExt(new Extensions());
        }

        /* Add protocol extension. */
        dest.getExt().setProtocol(new ProtocolExtension());
        dest.getExt().getProtocol().setDevModel(device.getModel());
        dest.getExt().getProtocol().setDevMake(device.getOemName());

        /* Add user extension. */
        dest.getExt().setUser(new UserExtension());
        dest.getExt().getUser().setLocalId(UserIdContext.getPrefixedUserId(src.getUserId()));
        dest.getExt().getUser().setLocale(device.getLocale().replace("_", "-"));

        /* Add OS extension. */
        dest.getExt().setOs(new OsExtension());
        dest.getExt().getOs().setName(device.getOsName());
        dest.getExt().getOs().setVer(device.getOsVersion() + "-" + device.getOsBuild() + "-" + device.getOsApiLevel());

        /* TODO: Add app locale. */
        /* Add app extension. */
        dest.getExt().setApp(new AppExtension());
        dest.getExt().getApp().setVer(device.getAppVersion());
        dest.getExt().getApp().setId("a" + COMMON_SCHEMA_PREFIX_SEPARATOR + device.getAppNamespace());

        /* TODO: Add network type. */
        /* Add net extension. */
        dest.getExt().setNet(new NetExtension());
        dest.getExt().getNet().setProvider(device.getCarrierName());

        /* Add SDK extension. */
        dest.getExt().setSdk(new SdkExtension());
        dest.getExt().getSdk().setLibVer(device.getSdkName() + "-" + device.getSdkVersion());

        /* Add loc extension. */
        dest.getExt().setLoc(new LocExtension());
        String timezoneOffset = String.format(Locale.US, "%s%02d:%02d",
                device.getTimeZoneOffset() >= 0 ? "+" : "-",
                Math.abs(device.getTimeZoneOffset() / 60),
                Math.abs(device.getTimeZoneOffset() % 60));
        dest.getExt().getLoc().setTz(timezoneOffset);

        /* Add device extension. */
        dest.getExt().setDevice(new DeviceExtension());
    }
}
