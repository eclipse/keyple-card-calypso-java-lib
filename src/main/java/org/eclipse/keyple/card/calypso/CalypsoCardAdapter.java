/* **************************************************************************************
 * Copyright (c) 2018 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information
 * regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ************************************************************************************** */
package org.eclipse.keyple.card.calypso;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.calypsonet.terminal.card.ApduResponseApi;
import org.calypsonet.terminal.card.CardSelectionResponseApi;
import org.calypsonet.terminal.card.spi.SmartCardSpi;
import org.eclipse.keyple.card.calypso.card.*;
import org.eclipse.keyple.card.calypso.transaction.CardTransactionService;
import org.eclipse.keyple.core.util.ByteArrayUtil;
import org.eclipse.keyple.core.util.json.JsonUtil;

/**
 * This POJO concentrates all the information we know about the card being processed: from the
 * selection stage to the end of the transaction.
 *
 * <p>An instance of CalypsoCard is obtained by casting the AbstractSmartCard object from the
 * selection process (e.g. (CalypsoCard)(cardSelectionsResult.getActiveSmartCard()))
 *
 * <p>The various information contained in CalypsoCard is accessible by getters and includes:
 *
 * <ul>
 *   <li>The application identification fields (revision/version, class, DF name, serial number,
 *       ATR, issuer)
 *   <li>The indication of the presence of optional features (Stored Value, PIN, Rev3.2 mode,
 *       ratification management)
 *   <li>The management information of the modification buffer
 *   <li>The invalidation status
 *   <li>The files, counters, SV data read or modified during the execution of the processes defined
 *       by {@link CardTransactionService}
 * </ul>
 *
 * @since 2.0
 */
final class CalypsoCardAdapter implements CalypsoCard, SmartCardSpi {
  private final byte[] fciBytes;
  private final boolean isConfidentialSessionModeSupported;
  private final boolean isDeselectRatificationSupported;
  private final boolean isSvFeatureAvailable;
  private final boolean isPinFeatureAvailable;
  private final boolean isPublicAuthenticationSupported;
  private final boolean isDfInvalidated;
  private final CalypsoCardClass calypsoCardClass;
  private final byte[] calypsoSerialNumber;
  private final byte[] startupInfo;
  private final CardRevision revision;
  private final byte[] dfName;
  private static final int CARD_REV1_ATR_LENGTH = 20;
  private static final int REV1_CARD_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION = 3;
  private static final int REV2_CARD_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION = 6;
  private static final int SI_BUFFER_SIZE_INDICATOR = 0;
  private static final int SI_PLATFORM = 1;
  private static final int SI_APPLICATION_TYPE = 2;
  private static final int SI_APPLICATION_SUBTYPE = 3;
  private static final int SI_SOFTWARE_ISSUER = 4;
  private static final int SI_SOFTWARE_VERSION = 5;
  private static final int SI_SOFTWARE_REVISION = 6;

  // Application type bitmasks features
  private static final byte APP_TYPE_WITH_CALYPSO_PIN = 0x01;
  private static final byte APP_TYPE_WITH_CALYPSO_SV = 0x02;
  private static final byte APP_TYPE_RATIFICATION_COMMAND_REQUIRED = 0x04;
  private static final byte APP_TYPE_CALYPSO_REV_32_MODE = 0x08;
  private static final byte APP_TYPE_WITH_PUBLIC_AUTHENTICATION = 0x10;

  // buffer indicator to buffer size lookup table
  private static final int[] BUFFER_SIZE_INDICATOR_TO_BUFFER_SIZE =
      new int[] {
        0, 0, 0, 0, 0, 0, 215, 256, 304, 362, 430, 512, 608, 724, 861, 1024, 1217, 1448, 1722, 2048,
        2435, 2896, 3444, 4096, 4870, 5792, 6888, 8192, 9741, 11585, 13777, 16384, 19483, 23170,
        27554, 32768, 38967, 46340, 55108, 65536, 77935, 92681, 110217, 131072, 155871, 185363,
        220435, 262144, 311743, 370727, 440871, 524288, 623487, 741455, 881743, 1048576
      };

  private final int modificationsCounterMax;
  private final byte[] powerOnData;
  private boolean modificationCounterIsInBytes = true;
  private DirectoryHeader directoryHeader;
  private final Map<Byte, ElementaryFile> efBySfi = new ConcurrentHashMap<Byte, ElementaryFile>();
  private final Map<Byte, ElementaryFile> efBySfiBackup =
      new ConcurrentHashMap<Byte, ElementaryFile>();
  private final Map<Short, Byte> sfiByLid = new ConcurrentHashMap<Short, Byte>();
  private final Map<Short, Byte> sfiByLidBackup = new ConcurrentHashMap<Short, Byte>();
  private Boolean isDfRatified = null;
  private Integer pinAttemptCounter;
  private Integer svBalance;
  private int svLastTNum;
  private SvLoadLogRecord svLoadLogRecord;
  private SvDebitLogRecord svDebitLogRecord;

  /**
   * Constructor.
   *
   * <p>Create the initial content from the data received in response to the card selection.
   *
   * @param cardSelectionResponse the response to the selection application command.
   * @since 2.0
   */
  CalypsoCardAdapter(CardSelectionResponseApi cardSelectionResponse) {

    ApduResponseApi fci = cardSelectionResponse.getSelectionStatus().getFci();
    if (fci != null) {
      this.fciBytes = fci.getBytes();
    } else {
      this.fciBytes = null;
    }

    powerOnData = cardSelectionResponse.getSelectionStatus().getPowerOnData();

    int bufferSizeIndicator;
    int bufferSizeValue;

    if (hasFci()) {

      /* Parse card FCI - to retrieve DF Name (AID), Serial Number, &amp; StartupInfo */
      CardGetDataFciParser cardGetDataFciParser =
          new CardGetDataFciParser(cardSelectionResponse.getSelectionStatus().getFci(), null);

      // 4 fields extracted by the low level parser
      dfName = cardGetDataFciParser.getDfName();
      calypsoSerialNumber = cardGetDataFciParser.getApplicationSerialNumber();
      startupInfo = cardGetDataFciParser.getDiscretionaryData();
      isDfInvalidated = cardGetDataFciParser.isDfInvalidated();

      byte applicationType = getApplicationType();
      revision = determineRevision(applicationType);

      // session buffer size
      bufferSizeIndicator = startupInfo[SI_BUFFER_SIZE_INDICATOR];
      bufferSizeValue = BUFFER_SIZE_INDICATOR_TO_BUFFER_SIZE[bufferSizeIndicator];

      if (revision == CardRevision.REV2_4) {
        /* old cards have their modification counter in number of commands */
        modificationCounterIsInBytes = false;
        modificationsCounterMax = REV2_CARD_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION;
      } else {
        modificationsCounterMax = bufferSizeValue;
      }
      isConfidentialSessionModeSupported = (applicationType & APP_TYPE_CALYPSO_REV_32_MODE) != 0;
      isDeselectRatificationSupported =
          (applicationType & APP_TYPE_RATIFICATION_COMMAND_REQUIRED) == 0;
      isSvFeatureAvailable = (applicationType & APP_TYPE_WITH_CALYPSO_SV) != 0;
      isPinFeatureAvailable = (applicationType & APP_TYPE_WITH_CALYPSO_PIN) != 0;
      isPublicAuthenticationSupported =
          (applicationType & APP_TYPE_WITH_PUBLIC_AUTHENTICATION) != 0;
    } else {
      /*
       * FCI is not provided: we consider it is Calypso card rev 1, it's serial number is
       * provided in the ATR
       */
      byte[] atr = getPowerOnData();
      /* basic check: we expect to be here following a selection based on the ATR */
      if (atr.length != CARD_REV1_ATR_LENGTH) {
        throw new IllegalStateException(
            "Unexpected ATR length: " + ByteArrayUtil.toHex(getPowerOnData()));
      }

      revision = CardRevision.REV1_0;
      dfName = null;
      calypsoSerialNumber = new byte[8];
      /* old cards have their modification counter in number of commands */
      modificationCounterIsInBytes = false;
      /*
       * the array is initialized with 0 (cf. default value for primitive types)
       */
      System.arraycopy(atr, 12, calypsoSerialNumber, 4, 4);
      modificationsCounterMax = REV1_CARD_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION;

      startupInfo = new byte[7];
      // create buffer size indicator
      startupInfo[0] = (byte) modificationsCounterMax;
      // create the startup info with the 6 bytes of the ATR from position 6
      System.arraycopy(atr, 6, startupInfo, 1, 6);

      // TODO check these flags
      isConfidentialSessionModeSupported = false;
      isDeselectRatificationSupported = true;
      isSvFeatureAvailable = false;
      isPinFeatureAvailable = false;
      isPublicAuthenticationSupported = false;
      isDfInvalidated = false;
    }
    /* Rev1 and Rev2 expects the legacy class byte while Rev3 expects the ISO class byte */
    if (revision == CardRevision.REV1_0 || revision == CardRevision.REV2_4) {
      calypsoCardClass = CalypsoCardClass.LEGACY;
    } else {
      calypsoCardClass = CalypsoCardClass.ISO;
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public boolean hasFci() {
    return this.fciBytes != null && this.fciBytes.length > 0;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public boolean hasPowerOnData() {
    return this.powerOnData != null && this.powerOnData.length > 0;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public byte[] getFciBytes() {
    if (this.hasFci()) {
      return this.fciBytes;
    } else {
      throw new IllegalStateException("No FCI is available in this AbstractSmartCard");
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public byte[] getPowerOnData() {
    if (this.hasPowerOnData()) {
      return this.powerOnData;
    } else {
      throw new IllegalStateException("No ATR is available in this AbstractSmartCard");
    }
  }

  /**
   * Resolve the card revision from the application type byte
   *
   * <ul>
   *   <li>if <code>%1-------</code>&nbsp;&nbsp;&rarr;&nbsp;&nbsp;CLAP&nbsp;&nbsp;&rarr;&nbsp;&
   *       nbsp; REV3.1
   *   <li>if <code>%00101---</code>&nbsp;&nbsp;&rarr;&nbsp;&nbsp;REV3.2
   *   <li>if <code>%00100---</code>&nbsp;&nbsp;&rarr;&nbsp;&nbsp;REV3.1
   *   <li>otherwise&nbsp;&nbsp;&rarr;&nbsp;&nbsp;REV2.4
   * </ul>
   *
   * @param applicationType the application type (field of startup info).
   * @return The {@link CardRevision}
   */
  private CardRevision determineRevision(byte applicationType) {
    if (((applicationType & 0xFF) & (1 << 7)) != 0) {
      /* CLAP */
      return CardRevision.REV3_1_CLAP;
    } else if ((applicationType >> 3) == (byte) (0x05)) {
      return CardRevision.REV3_2;
    } else if ((applicationType >> 3) == (byte) (0x04)) {
      return CardRevision.REV3_1;
    } else {
      return CardRevision.REV2_4;
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final CardRevision getRevision() {
    return revision;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte[] getDfNameBytes() {
    return dfName;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final String getDfName() {
    return ByteArrayUtil.toHex(getDfNameBytes());
  }

  /**
   * Gets the full Calypso serial number including the possible validity date information in the two
   * MSB.
   *
   * <p>The serial number to be used as diversifier for key derivation.<br>
   * This is the complete number returned by the card in its response to the Select command.
   *
   * @return A byte array containing the Calypso Serial Number (8 bytes)
   * @since 2.0
   */
  protected final byte[] getCalypsoSerialNumber() {
    return calypsoSerialNumber;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte[] getApplicationSerialNumberBytes() {
    byte[] applicationSerialNumber = calypsoSerialNumber.clone();
    applicationSerialNumber[0] = 0;
    applicationSerialNumber[1] = 0;
    return applicationSerialNumber;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final String getApplicationSerialNumber() {
    return ByteArrayUtil.toHex(getApplicationSerialNumberBytes());
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final String getStartupInfo() {
    return ByteArrayUtil.toHex(startupInfo);
  }

  protected final boolean isSerialNumberExpiring() {
    throw new IllegalStateException("Not yet implemented");
  }

  protected final byte[] getSerialNumberExpirationBytes() {
    throw new IllegalStateException("Not yet implemented");
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final String getPowerOnDataString() {
    return ByteArrayUtil.toHex(powerOnData);
  }

  /**
   * Gets the maximum length of data that an APDU in this card can carry.
   *
   * @return An int
   * @since 2.0
   */
  protected final int getPayloadCapacity() {
    // TODO make this value dependent on the type of card identified
    return 250;
  }

  /**
   * Tells if the change counter allowed in session is established in number of operations or number
   * of bytes modified.
   *
   * <p>This varies depending on the revision of the card.
   *
   * @return True if the counter is number of bytes
   * @since 2.0
   */
  protected final boolean isModificationsCounterInBytes() {
    return modificationCounterIsInBytes;
  }

  /**
   * Indicates the maximum number of changes allowed in session.
   *
   * <p>This number can be a number of operations or a number of commands (see
   * isModificationsCounterInBytes)
   *
   * @return The maximum number of modifications allowed
   * @since 2.0
   */
  protected final int getModificationsCounter() {
    return modificationsCounterMax;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getPlatform() {
    return startupInfo[SI_PLATFORM];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getApplicationType() {
    return startupInfo[SI_APPLICATION_TYPE];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isConfidentialSessionModeSupported() {
    return isConfidentialSessionModeSupported;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isDeselectRatificationSupported() {
    return isDeselectRatificationSupported;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isSvFeatureAvailable() {
    return isSvFeatureAvailable;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isPinFeatureAvailable() {
    return isPinFeatureAvailable;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isPublicAuthenticationSupported() {
    return isPublicAuthenticationSupported;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getApplicationSubtype() {
    return startupInfo[SI_APPLICATION_SUBTYPE];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getSoftwareIssuer() {
    return startupInfo[SI_SOFTWARE_ISSUER];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getSoftwareVersion() {
    return startupInfo[SI_SOFTWARE_VERSION];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getSoftwareRevision() {
    return startupInfo[SI_SOFTWARE_REVISION];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final byte getSessionModification() {
    return startupInfo[SI_BUFFER_SIZE_INDICATOR];
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isDfInvalidated() {
    return isDfInvalidated;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isDfRatified() {
    if (isDfRatified != null) {
      return isDfRatified;
    }
    throw new IllegalStateException(
        "Unable to determine the ratification status. No session was opened.");
  }

  /**
   * (package-private)<br>
   * Sets the Stored Value data from the SV Get command
   *
   * @param svBalance the current SV balance.
   * @param svLastTNum the last SV transaction number.
   * @param svLoadLogRecord the SV load log record (may be null if not available).
   * @param svDebitLogRecord the SV debit log record (may be null if not available).
   * @since 2.0
   */
  final void setSvData(
      int svBalance,
      int svLastTNum,
      SvLoadLogRecord svLoadLogRecord,
      SvDebitLogRecord svDebitLogRecord) {
    this.svBalance = svBalance;
    this.svLastTNum = svLastTNum;
    // update logs, do not overwrite existing values (case of double reading)
    if (this.svLoadLogRecord == null) {
      this.svLoadLogRecord = svLoadLogRecord;
    }
    if (this.svDebitLogRecord == null) {
      this.svDebitLogRecord = svDebitLogRecord;
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final int getSvBalance() {
    if (svBalance == null) {
      throw new IllegalStateException("No SV Get command has been executed.");
    }
    return svBalance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final int getSvLastTNum() {
    if (svBalance == null) {
      throw new IllegalStateException("No SV Get command has been executed.");
    }
    return svLastTNum;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final SvLoadLogRecord getSvLoadLogRecord() {
    if (svLoadLogRecord == null) {
      // try to get it from the file data
      byte[] logRecord =
          getFileBySfi(CalypsoCardUtils.SV_RELOAD_LOG_FILE_SFI).getData().getContent();
      svLoadLogRecord = new SvLoadLogRecordAdapter(logRecord, 0);
    }
    return svLoadLogRecord;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final SvDebitLogRecord getSvDebitLogLastRecord() {
    if (svDebitLogRecord == null) {
      // try to get it from the file data
      List<SvDebitLogRecord> svDebitLogRecords = getSvDebitLogAllRecords();
      svDebitLogRecord = svDebitLogRecords.get(0);
    }
    return svDebitLogRecord;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final List<SvDebitLogRecord> getSvDebitLogAllRecords() {
    // get the logs from the file data
    SortedMap<Integer, byte[]> logRecords =
        getFileBySfi(CalypsoCardUtils.SV_DEBIT_LOG_FILE_SFI).getData().getAllRecordsContent();
    List<SvDebitLogRecord> svDebitLogRecords = new ArrayList<SvDebitLogRecord>();
    for (Map.Entry<Integer, byte[]> entry : logRecords.entrySet()) {
      svDebitLogRecords.add(new SvDebitLogRecordAdapter(entry.getValue(), 0));
    }
    return svDebitLogRecords;
  }

  /**
   * (package-private)<br>
   * Sets the ratification status
   *
   * @param dfRatified true if the session was ratified.
   */
  final void setDfRatified(boolean dfRatified) {
    isDfRatified = dfRatified;
  }

  /**
   * The card class is the ISO7816 class to be used with the current card.
   *
   * <p>It determined from the card revision
   *
   * <p>Two classes are possible: LEGACY and ISO.
   *
   * @return the card class determined from the card revision
   */
  protected final CalypsoCardClass getCardClass() {
    return calypsoCardClass;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final DirectoryHeader getDirectoryHeader() {
    return directoryHeader;
  }

  /**
   * (package-private)<br>
   * Sets the DF metadata.
   *
   * @param directoryHeader the DF metadata (should be not null).
   * @return the current instance.
   */
  final CalypsoCard setDirectoryHeader(DirectoryHeader directoryHeader) {
    this.directoryHeader = directoryHeader;
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final ElementaryFile getFileBySfi(byte sfi) {
    ElementaryFile ef = efBySfi.get(sfi);
    if (ef == null) {
      throw new NoSuchElementException(
          "EF with SFI [0x" + Integer.toHexString(sfi & 0xFF) + "] is not found.");
    }
    return ef;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final ElementaryFile getFileByLid(short lid) {
    Byte sfi = sfiByLid.get(lid);
    if (sfi == null) {
      throw new NoSuchElementException(
          "EF with LID [" + Integer.toHexString(lid & 0xFFFF) + "] is not found.");
    }
    return efBySfi.get(sfi);
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final Map<Byte, ElementaryFile> getAllFiles() {
    return efBySfi;
  }

  /**
   * (private)<br>
   * Gets or creates the EF having the provided SFI.
   *
   * @param sfi the SFI.
   * @return a not null reference.
   */
  private ElementaryFileAdapter getOrCreateFile(byte sfi) {
    ElementaryFileAdapter ef = (ElementaryFileAdapter) efBySfi.get(sfi);
    if (ef == null) {
      ef = new ElementaryFileAdapter(sfi);
      efBySfi.put(sfi, ef);
    }
    return ef;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final boolean isPinBlocked() {
    return getPinAttemptRemaining() == 0;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public final int getPinAttemptRemaining() {
    if (pinAttemptCounter == null) {
      throw new IllegalStateException("PIN status has not been checked.");
    }
    return pinAttemptCounter;
  }

  /**
   * (package-private)<br>
   * Sets the PIN attempts counter.<br>
   * The PIN attempt counter is interpreted to give the results of the methods {@link #isPinBlocked}
   * and {@link #getPinAttemptRemaining}.
   *
   * @param pinAttemptCounter the number of remaining attempts to present the PIN code.
   */
  final void setPinAttemptRemaining(int pinAttemptCounter) {
    this.pinAttemptCounter = pinAttemptCounter;
  }

  /**
   * (package-private)<br>
   * Sets the provided {@link FileHeader} to the EF having the provided SFI.<br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI.
   * @param header the file header (should be not null).
   */
  final void setFileHeader(byte sfi, FileHeader header) {
    ElementaryFileAdapter ef = getOrCreateFile(sfi);
    ef.setHeader(header);
    sfiByLid.put(header.getLid(), sfi);
  }

  /**
   * (package-private)<br>
   * Set or replace the entire content of the specified record #numRecord of the provided SFI by the
   * provided content.<br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI.
   * @param numRecord the record number (should be {@code >=} 1).
   * @param content the content (should be not empty).
   */
  final void setContent(byte sfi, int numRecord, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ((FileDataAdapter) ef.getData()).setContent(numRecord, content);
  }

  /**
   * (package-private)<br>
   * Sets a counter value in record #1 of the provided SFI.<br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI.
   * @param numCounter the counter number (should be {@code >=} 1).
   * @param content the counter value (should be not null and 3 bytes length).
   */
  final void setCounter(byte sfi, int numCounter, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ((FileDataAdapter) ef.getData()).setCounter(numCounter, content);
  }

  /**
   * (package-private)<br>
   * Set or replace the content at the specified offset of record #numRecord of the provided SFI by
   * a copy of the provided content.<br>
   * If EF does not exist, then it is created.<br>
   * If actual record content is not set or has a size {@code <} offset, then missing data will be
   * padded with 0.
   *
   * @param sfi the SFI.
   * @param numRecord the record number (should be {@code >=} 1).
   * @param content the content (should be not empty).
   * @param offset the offset (should be {@code >=} 0).
   */
  final void setContent(byte sfi, int numRecord, byte[] content, int offset) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ((FileDataAdapter) ef.getData()).setContent(numRecord, content, offset);
  }

  /**
   * (package-private)<br>
   * Fill the content of the specified #numRecord of the provided SFI using a binary OR operation
   * with the provided content.<br>
   * If EF does not exist, then it is created.<br>
   * If actual record content is not set or has a size {@code <} content size, then missing data
   * will be completed by the provided content.
   *
   * @param sfi the SFI.
   * @param numRecord the record number (should be {@code >=} 1).
   * @param content the content (should be not empty).
   */
  final void fillContent(byte sfi, int numRecord, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ((FileDataAdapter) ef.getData()).fillContent(numRecord, content);
  }

  /**
   * (package-private)<br>
   * Add cyclic content at record #1 by rolling previously all actual records contents (record #1 ->
   * record #2, record #2 -> record #3,...) of the provided SFI.<br>
   * This is useful for cyclic files. Note that records are infinitely shifted.<br>
   * <br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI.
   * @param content the content (should be not empty).
   */
  final void addCyclicContent(byte sfi, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ((FileDataAdapter) ef.getData()).addCyclicContent(content);
  }

  /**
   * (package-private)<br>
   * Make a backup of the Elementary Files.<br>
   * This method should be used before starting a card secure session.
   */
  final void backupFiles() {
    copyMapFiles(efBySfi, efBySfiBackup);
    copyMapSfi(sfiByLid, sfiByLidBackup);
  }

  /**
   * (package-private)<br>
   * Restore the last backup of Elementary Files.<br>
   * This method should be used when SW of the card close secure session command is unsuccessful or
   * if secure session is aborted.
   */
  final void restoreFiles() {
    copyMapFiles(efBySfiBackup, efBySfi);
    copyMapSfi(sfiByLidBackup, sfiByLid);
  }

  /**
   * (private)<br>
   * Copy a map of ElementaryFile by SFI to another one by cloning each element.
   *
   * @param src the source (should be not null).
   * @param dest the destination (should be not null).
   */
  private static void copyMapFiles(Map<Byte, ElementaryFile> src, Map<Byte, ElementaryFile> dest) {
    dest.clear();
    for (Map.Entry<Byte, ElementaryFile> entry : src.entrySet()) {
      dest.put(entry.getKey(), new ElementaryFileAdapter(entry.getValue()));
    }
  }

  /**
   * (private)<br>
   * Copy a map of SFI by LID to another one by cloning each element.
   *
   * @param src the source (should be not null).
   * @param dest the destination (should be not null).
   */
  private static void copyMapSfi(Map<Short, Byte> src, Map<Short, Byte> dest) {
    dest.clear();
    dest.putAll(src);
  }

  /**
   * Gets the object content as a Json string.
   *
   * @return A not empty string.
   * @since 2.0
   */
  @Override
  public String toString() {
    return JsonUtil.toJson(this);
  }
}
