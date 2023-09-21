/* **************************************************************************************
 * Copyright (c) 2023 Calypso Networks Association https://calypsonet.org/
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

import static org.eclipse.keyple.card.calypso.DtoAdapters.*;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.keyple.core.util.Assert;
import org.eclipse.keypop.calypso.card.WriteAccessLevel;
import org.eclipse.keypop.calypso.card.card.CalypsoCard;
import org.eclipse.keypop.calypso.card.transaction.*;
import org.eclipse.keypop.calypso.card.transaction.ChannelControl;
import org.eclipse.keypop.calypso.card.transaction.spi.CardTransactionCryptoExtension;
import org.eclipse.keypop.calypso.crypto.symmetric.SymmetricCryptoException;
import org.eclipse.keypop.calypso.crypto.symmetric.SymmetricCryptoIOException;
import org.eclipse.keypop.calypso.crypto.symmetric.spi.SymmetricCryptoTransactionManagerFactorySpi;
import org.eclipse.keypop.calypso.crypto.symmetric.spi.SymmetricCryptoTransactionManagerSpi;
import org.eclipse.keypop.card.*;
import org.eclipse.keypop.reader.CardReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter of {@link SecureSymmetricCryptoTransactionManager}.
 *
 * @param <T> The type of the lowest level child object.
 * @since 3.0.0
 */
abstract class SecureSymmetricCryptoTransactionManagerAdapter<
        T extends SecureSymmetricCryptoTransactionManager<T>>
    extends SecureTransactionManagerAdapter<T>
    implements SecureSymmetricCryptoTransactionManager<T> {

  private static final Logger logger =
      LoggerFactory.getLogger(SecureSymmetricCryptoTransactionManagerAdapter.class);

  // commands that modify the content of the card in session have a cost on the session buffer equal
  // to the length of the outgoing data plus 6 bytes
  private static final int SESSION_BUFFER_CMD_ADDITIONAL_COST = 6;
  private static final int APDU_HEADER_LENGTH = 5;

  private final SymmetricCryptoSecuritySettingAdapter symmetricCryptoSecuritySetting;
  private final SymmetricCryptoTransactionManagerSpi symmetricCryptoTransactionManagerSpi;
  private final CardTransactionCryptoExtension cryptoExtension;
  private WriteAccessLevel writeAccessLevel;
  private final int payloadCapacity;
  private int modificationsCounter;
  private int nbPostponedData;
  private int svPostponedDataIndex = -1;
  private boolean isSvGet;
  private SvOperation svOperation;
  private SvAction svAction;
  private boolean isSvOperationInSecureSession;

  final TransactionContextDto transactionContext; // package-private for perf optimization
  boolean isExtendedMode; // package-private for perf optimization
  boolean isEncryptionActive; // package-private for perf optimization

  /**
   * Builds a new instance.
   *
   * @param cardReader The card reader to be used.
   * @param card The selected card on which to operate the transaction.
   * @param symmetricCryptoSecuritySetting The symmetric crypto security setting to be used.
   * @since 3.0.0
   */
  SecureSymmetricCryptoTransactionManagerAdapter(
      ProxyReaderApi cardReader,
      CalypsoCardAdapter card,
      SymmetricCryptoSecuritySettingAdapter symmetricCryptoSecuritySetting) {
    super(cardReader, card);

    this.symmetricCryptoSecuritySetting = symmetricCryptoSecuritySetting;

    SymmetricCryptoTransactionManagerFactorySpi cryptoFactory =
        symmetricCryptoSecuritySetting.getCryptoTransactionManagerFactorySpi();
    // Extended mode flag
    isExtendedMode = card.isExtendedModeSupported() && cryptoFactory.isExtendedModeSupported();
    if (!isExtendedMode) {
      disablePreOpenMode();
    }
    // Adjust card & SAM payload capacities
    payloadCapacity =
        Math.min(
            card.getPayloadCapacity(),
            cryptoFactory.getMaxCardApduLengthSupported() - APDU_HEADER_LENGTH);
    // CL-SAM-CSN.1
    symmetricCryptoTransactionManagerSpi =
        cryptoFactory.createTransactionManager(
            card.getCalypsoSerialNumberFull(), isExtendedMode, getTransactionAuditData());
    cryptoExtension = (CardTransactionCryptoExtension) symmetricCryptoTransactionManagerSpi;

    transactionContext = new TransactionContextDto(card, symmetricCryptoTransactionManagerSpi);
    modificationsCounter = card.getModificationsCounter();
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final TransactionContextDto getTransactionContext() {
    return transactionContext;
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final CommandContextDto getCommandContext() {
    return new CommandContextDto(isSecureSessionOpen, isEncryptionActive);
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final void resetCommandContext() {
    isSecureSessionOpen = false;
    isEncryptionActive = false;
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final int getPayloadCapacity() {
    return payloadCapacity;
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final void resetTransaction() {
    resetCommandContext();
    modificationsCounter = card.getModificationsCounter();
    nbPostponedData = 0;
    svPostponedDataIndex = -1;
    isSvGet = false;
    svOperation = null;
    isSvOperationInSecureSession = false;
    disablePreOpenMode();
    cardCommands.clear();
    if (getTransactionContext().isSecureSessionOpen()) {
      try {
        CmdCardCloseSecureSession cancelSecureSessionCommand =
            new CmdCardCloseSecureSession(getTransactionContext(), getCommandContext());
        cancelSecureSessionCommand.finalizeRequest();
        List<CardCommand> commands = new ArrayList<CardCommand>(1);
        commands.add(cancelSecureSessionCommand);
        executeCardCommands(commands, ChannelControl.KEEP_OPEN);
      } catch (RuntimeException e) {
        logger.debug("Secure session abortion error: {}", e.getMessage());
      } finally {
        card.restoreFiles();
        getTransactionContext().setSecureSessionOpen(false);
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final void prepareNewSecureSessionIfNeeded(CardCommand command) {
    if (!isSecureSessionOpen) {
      return;
    }
    modificationsCounter -= computeCommandSessionBufferSize(command);
    if (modificationsCounter < 0) {
      checkMultipleSessionEnabled(command);
      cardCommands.add(
          new CmdCardCloseSecureSession(
              transactionContext, getCommandContext(), true, svPostponedDataIndex));
      disablePreOpenMode();
      cardCommands.add(
          new CmdCardOpenSecureSession(
              transactionContext,
              getCommandContext(),
              symmetricCryptoSecuritySetting,
              writeAccessLevel,
              isExtendedMode));
      if (isEncryptionActive) {
        cardCommands.add(
            new CmdCardManageSession(transactionContext, getCommandContext())
                .setEncryptionRequested(true));
      }
      modificationsCounter = card.getModificationsCounter();
      modificationsCounter -= computeCommandSessionBufferSize(command);
      nbPostponedData = 0;
      svPostponedDataIndex = -1;
      isSvOperationInSecureSession = false;
    }
  }

  /**
   * Computes the session buffer size of the provided command.<br>
   * The size may be a number of bytes or 1 depending on the card specificities.
   *
   * @param command The command.
   * @return A positive value.
   */
  private int computeCommandSessionBufferSize(CardCommand command) {
    return card.isModificationsCounterInBytes()
        ? command.getApduRequest().getApdu().length
            + SESSION_BUFFER_CMD_ADDITIONAL_COST
            - APDU_HEADER_LENGTH
        : 1;
  }

  /**
   * Throws an exception if the multiple session is not enabled.
   *
   * @param command The command.
   * @throws SessionBufferOverflowException If the multiple session is not allowed.
   */
  private void checkMultipleSessionEnabled(CardCommand command) {
    // CL-CSS-REQUEST.1
    // CL-CSS-SMEXCEED.1
    // CL-CSS-INFOCSS.1
    if (!symmetricCryptoSecuritySetting.isMultipleSessionEnabled()) {
      throw new SessionBufferOverflowException(
          "ATOMIC mode error! This command would overflow the card modifications buffer: "
              + command.getName()
              + getTransactionAuditDataAsString());
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final boolean canConfigureReadOnOpenSecureSession() {
    return isSecureSessionOpen
        && !symmetricCryptoSecuritySetting.isReadOnSessionOpeningDisabled()
        && card.getPreOpenWriteAccessLevel() == null // No pre-open mode
        && !cardCommands.isEmpty()
        && cardCommands.get(cardCommands.size() - 1).getCommandRef()
            == CardCommandRef.OPEN_SECURE_SESSION
        && !((CmdCardOpenSecureSession) cardCommands.get(cardCommands.size() - 1))
            .isReadModeConfigured();
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  final T prepareIncreaseOrDecreaseCounter(
      boolean isDecreaseCommand, byte sfi, int counterNumber, int incDecValue) {
    super.prepareIncreaseOrDecreaseCounter(isDecreaseCommand, sfi, counterNumber, incDecValue);
    if (getCommandContext().isSecureSessionOpen() && card.isCounterValuePostponed()) {
      nbPostponedData++;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * <p>For each prepared command, if a pre-processing is required, then we try to execute the
   * post-processing of each of the previous commands in anticipation. If at least one
   * post-processing cannot be anticipated, then we execute the block of previous commands first.
   *
   * @since 2.3.2
   */
  @Override
  public final T processCommands(ChannelControl channelControl) {
    if (cardCommands.isEmpty()) {
      processCryptoPreparedCommands();
      return currentInstance;
    }
    try {
      List<CardCommand> cardRequestCommands = new ArrayList<CardCommand>();
      for (CardCommand command : cardCommands) {
        if (command.isCryptoServiceRequiredToFinalizeRequest()
            && (!synchronizeCryptoServiceBeforeCardProcessing(cardRequestCommands))) {
          executeCardCommands(cardRequestCommands, ChannelControl.KEEP_OPEN);
          cardRequestCommands.clear();
        }
        command.finalizeRequest();
        cardRequestCommands.add(command);
      }
      executeCardCommands(cardRequestCommands, channelControl);
      processCryptoPreparedCommands();
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    } finally {
      cardCommands.clear();
      if (isExtendedMode && !card.isExtendedModeSupported()) {
        isExtendedMode = false;
      }
    }
    return currentInstance;
  }

  /**
   * Attempts to synchronize the crypto service before executing the finalized command on the card
   * and returns "true" on successful execution.
   *
   * @param commands The commands.
   * @return "false" if the crypto service could not be synchronized before transmitting the
   *     commands to the card.
   */
  private boolean synchronizeCryptoServiceBeforeCardProcessing(List<CardCommand> commands) {
    for (CardCommand command : commands) {
      if (!command.synchronizeCryptoServiceBeforeCardProcessing()) {
        return false;
      }
    }
    return true;
  }

  /** Process any prepared crypto commands. */
  private void processCryptoPreparedCommands() {
    if (symmetricCryptoTransactionManagerSpi != null) {
      try {
        symmetricCryptoTransactionManagerSpi.synchronize();
      } catch (SymmetricCryptoException e) {
        throw (RuntimeException) e.getCause();
      } catch (SymmetricCryptoIOException e) {
        throw (RuntimeException) e.getCause();
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.3.2
   */
  @Override
  public final T prepareVerifyPin(byte[] pin) {
    try {
      Assert.getInstance()
          .notNull(pin, "pin")
          .isEqual(pin.length, CalypsoCardConstant.PIN_LENGTH, "PIN length");
      if (!card.isPinFeatureAvailable()) {
        throw new UnsupportedOperationException(MSG_PIN_NOT_AVAILABLE);
      }
      if (symmetricCryptoSecuritySetting == null
          || symmetricCryptoSecuritySetting.isPinPlainTransmissionEnabled()) {
        cardCommands.add(new CmdCardVerifyPin(getTransactionContext(), getCommandContext(), pin));
      } else {
        // CL-PIN-PENCRYPT.1
        // CL-PIN-GETCHAL.1
        cardCommands.add(new CmdCardGetChallenge(getTransactionContext(), getCommandContext()));
        cardCommands.add(
            new CmdCardVerifyPin(
                getTransactionContext(),
                getCommandContext(),
                pin,
                symmetricCryptoSecuritySetting.getPinVerificationCipheringKif(),
                symmetricCryptoSecuritySetting.getPinVerificationCipheringKvc()));
      }
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.3.2
   */
  @Override
  public final T prepareChangePin(byte[] newPin) {
    try {
      Assert.getInstance()
          .notNull(newPin, "newPin")
          .isEqual(newPin.length, CalypsoCardConstant.PIN_LENGTH, "PIN length");
      if (!card.isPinFeatureAvailable()) {
        throw new UnsupportedOperationException(MSG_PIN_NOT_AVAILABLE);
      }
      checkNoSecureSession();
      // CL-PIN-MENCRYPT.1
      if (symmetricCryptoSecuritySetting == null
          || symmetricCryptoSecuritySetting.isPinPlainTransmissionEnabled()) {
        cardCommands.add(
            new CmdCardChangePin(getTransactionContext(), getCommandContext(), newPin));
      } else {
        // CL-PIN-GETCHAL.1
        cardCommands.add(new CmdCardGetChallenge(getTransactionContext(), getCommandContext()));
        cardCommands.add(
            new CmdCardChangePin(
                getTransactionContext(),
                getCommandContext(),
                newPin,
                symmetricCryptoSecuritySetting.getPinModificationCipheringKif(),
                symmetricCryptoSecuritySetting.getPinModificationCipheringKvc()));
      }
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 3.0.0
   */
  @Override
  public final <E extends CardTransactionCryptoExtension> E getCryptoExtension(
      Class<E> cryptoExtensionClass) {
    return (E) cryptoExtension;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.3.2
   */
  @Override
  public final T prepareOpenSecureSession(WriteAccessLevel writeAccessLevel) {
    try {
      Assert.getInstance().notNull(writeAccessLevel, "writeAccessLevel");
      checkNoSecureSession();
      if (card.getPreOpenWriteAccessLevel() != null
          && card.getPreOpenWriteAccessLevel() != writeAccessLevel) {
        logger.warn(
            "Pre-open mode cancelled because writeAccessLevel '{}' mismatches the writeAccessLevel used for"
                + " pre-open mode '{}'",
            writeAccessLevel,
            card.getPreOpenWriteAccessLevel());
        disablePreOpenMode();
      }
      cardCommands.add(
          new CmdCardOpenSecureSession(
              transactionContext,
              getCommandContext(),
              symmetricCryptoSecuritySetting,
              writeAccessLevel,
              isExtendedMode));
      this.writeAccessLevel = writeAccessLevel; // CL-KEY-INDEXPO.1
      isSecureSessionOpen = true;
      isEncryptionActive = false;
      modificationsCounter = card.getModificationsCounter();
      nbPostponedData = 0;
      svPostponedDataIndex = -1;
      isSvOperationInSecureSession = false;
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.3.2
   */
  @Override
  public final T prepareCloseSecureSession() {
    try {
      checkSecureSession();
      if (symmetricCryptoSecuritySetting.isRatificationMechanismEnabled()
          && ((CardReader) cardReader).isContactless()) {
        // CL-RAT-CMD.1
        // CL-RAT-DELAY.1
        // CL-RAT-NXTCLOSE.1
        cardCommands.add(
            new CmdCardCloseSecureSession(
                getTransactionContext(), getCommandContext(), false, svPostponedDataIndex));
        cardCommands.add(new CmdCardRatification(getTransactionContext(), getCommandContext()));
      } else {
        cardCommands.add(
            new CmdCardCloseSecureSession(
                getTransactionContext(), getCommandContext(), true, svPostponedDataIndex));
      }
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    } finally {
      resetCommandContext();
      disablePreOpenMode();
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareSvGet(SvOperation svOperation, SvAction svAction) {
    try {
      Assert.getInstance().notNull(svOperation, "svOperation").notNull(svAction, "svAction");

      if (!card.isSvFeatureAvailable()) {
        throw new UnsupportedOperationException("Stored Value is not available for this card.");
      }

      if (symmetricCryptoSecuritySetting.isSvLoadAndDebitLogEnabled() && (!isExtendedMode)) {
        // @see Calypso Layer ID 8.09/8.10 (200108): both reload and debit logs are requested
        // for a non rev3.2 card add two SvGet commands (for RELOAD then for DEBIT).
        // CL-SV-GETNUMBER.1
        SvOperation operation1 =
            svOperation == SvOperation.RELOAD ? SvOperation.DEBIT : SvOperation.RELOAD;
        cardCommands.add(
            new CmdCardSvGet(transactionContext, getCommandContext(), operation1, false));
      }
      cardCommands.add(
          new CmdCardSvGet(transactionContext, getCommandContext(), svOperation, isExtendedMode));
      isSvGet = true;
      this.svOperation = svOperation;
      this.svAction = svAction;
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareSvReload(int amount, byte[] date, byte[] time, byte[] free) {
    try {
      Assert.getInstance()
          .isInRange(
              amount,
              CalypsoCardConstant.SV_LOAD_MIN_VALUE,
              CalypsoCardConstant.SV_LOAD_MAX_VALUE,
              "amount")
          .notNull(date, "date")
          .notNull(time, "time")
          .notNull(free, "free")
          .isEqual(date.length, 2, "date")
          .isEqual(time.length, 2, "time")
          .isEqual(free.length, 2, "free");

      checkSvModifyingCommandPreconditions(SvOperation.RELOAD);

      CmdCardSvReload command =
          new CmdCardSvReload(
              transactionContext, getCommandContext(), amount, date, time, free, isExtendedMode);
      prepareNewSecureSessionIfNeeded(command);
      cardCommands.add(command);

    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * Checks if the preconditions of an SV modifying command are satisfied and updates the
   * corresponding flags.
   *
   * @throws IllegalStateException If preconditions are not satisfied.
   */
  private void checkSvModifyingCommandPreconditions(SvOperation svOperation) {
    // CL-SV-GETDEBIT.1
    // CL-SV-GETRLOAD.1
    if (!isSvGet) {
      throw new IllegalStateException("SV modifying command must follow an SV Get command");
    }
    isSvGet = false;
    if (svOperation != this.svOperation) {
      throw new IllegalStateException("Inconsistent SV operation");
    }
    // CL-SV-1PCSS.1
    if (isSecureSessionOpen) {
      if (isSvOperationInSecureSession) {
        throw new IllegalStateException(
            "Only one SV modifying command is allowed per Secure Session");
      }
      isSvOperationInSecureSession = true;
      svPostponedDataIndex = nbPostponedData;
      nbPostponedData++;
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareSvReload(int amount) {
    byte[] zero = {0x00, 0x00};
    prepareSvReload(amount, zero, zero, zero);
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareSvDebit(int amount, byte[] date, byte[] time) {
    try {
      /* @see Calypso Layer ID 8.02 (200108) */
      // CL-SV-DEBITVAL.1
      Assert.getInstance()
          .isInRange(
              amount,
              CalypsoCardConstant.SV_DEBIT_MIN_VALUE,
              CalypsoCardConstant.SV_DEBIT_MAX_VALUE,
              "amount")
          .notNull(date, "date")
          .notNull(time, "time")
          .isEqual(date.length, 2, "date")
          .isEqual(time.length, 2, "time");

      checkSvModifyingCommandPreconditions(SvOperation.DEBIT);

      CmdCardSvDebitOrUndebit command =
          new CmdCardSvDebitOrUndebit(
              svAction == SvAction.DO,
              transactionContext,
              getCommandContext(),
              amount,
              date,
              time,
              isExtendedMode,
              symmetricCryptoSecuritySetting.isSvNegativeBalanceAuthorized());
      prepareNewSecureSessionIfNeeded(command);
      cardCommands.add(command);

    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareSvDebit(int amount) {
    byte[] zero = {0x00, 0x00};
    prepareSvDebit(amount, zero, zero);
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareInvalidate() {
    try {
      if (card.isDfInvalidated()) {
        throw new IllegalStateException("Card already invalidated");
      }
      CmdCardInvalidate command = new CmdCardInvalidate(transactionContext, getCommandContext());
      prepareNewSecureSessionIfNeeded(command);
      cardCommands.add(command);
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0.0
   */
  @Override
  public final T prepareRehabilitate() {
    try {
      if (!card.isDfInvalidated()) {
        throw new IllegalStateException("Card not invalidated");
      }
      CmdCardRehabilitate command =
          new CmdCardRehabilitate(transactionContext, getCommandContext());
      prepareNewSecureSessionIfNeeded(command);
      cardCommands.add(command);
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.3.2
   */
  @Override
  public final T prepareChangeKey(
      int keyIndex, byte newKif, byte newKvc, byte issuerKif, byte issuerKvc) {
    try {
      if (card.getProductType() == CalypsoCard.ProductType.BASIC) {
        throw new UnsupportedOperationException("'Change Key' command not available for this card");
      }
      checkNoSecureSession();
      Assert.getInstance().isInRange(keyIndex, 1, 3, "keyIndex");
      // CL-KEY-CHANGE.1
      cardCommands.add(new CmdCardGetChallenge(transactionContext, getCommandContext()));
      cardCommands.add(
          new CmdCardChangeKey(
              transactionContext,
              getCommandContext(),
              (byte) keyIndex,
              newKif,
              newKvc,
              issuerKif,
              issuerKvc));
    } catch (RuntimeException e) {
      resetTransaction();
      throw e;
    }
    return currentInstance;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.3.4
   */
  @Override
  public final void initCryptoContextForNextTransaction() {
    if (!cardCommands.isEmpty()) {
      throw new IllegalStateException("Unprocessed card commands are pending");
    }
    try {
      symmetricCryptoTransactionManagerSpi.preInitTerminalSecureSessionContext();
    } catch (SymmetricCryptoException e) {
      throw (RuntimeException) e.getCause();
    } catch (SymmetricCryptoIOException e) {
      throw (RuntimeException) e.getCause();
    }
  }
}