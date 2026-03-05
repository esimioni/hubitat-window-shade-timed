// MIT License
// Copyright (c) 2021 Eduardo Simioni
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the "Software"), to deal in the Software without restriction,
// including without limitation the rights to use, copy, modify, merge, publish, distribute,
// sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or
// substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
// BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

/**
 * Zigbee Window Shade/Blind (Timed)
 *
 * Driver for Zigbee curtain/blind modules that estimates position based on movement time, command and motor delays.
 * Designed for devices that don't report their position.
 *
 * Supported devices:
 *   - Zemismart ZW-EC-01 (Tuya) - Not recommended! Can't disable calibration. Sometimes drops from the Zigbee network.
 *   - LoraTap SC500ZB (Tuya) - Works ok, much weaker Zigbee signal than Sonoff, but no other issues.
 *   - Sonoff MINI-ZBRBS (ZCL) - Strongly recommended! Powerful Zigbee signal. Works perfectly.
 *   - ZCL: Should work with any device that follows ZCL standards.
 *   - Tuya: May work with similar devices that don't follow ZCL but have similar command structure, but not tested.
 *
 * Features:
 *   - Time-based position estimation with configurable open/close durations and motor delays.
 *   - Semi-blind position for cellular shades (position 3%) for precise cell positioning.
 *   - Inverted movement near lower limit for improved accuracy.
 *   - Turbo Mode for Sonoff devices (increased Zigbee radio power).
 *   - Safety timeout to recover from missed pause commands.
 *
 * Installation:
 *   - When first using a device with this driver, use the "Reset Position" to set the current position of the shade.
 *     This will initialize the driver's state to match the actual position of the shade.
 *
 *   - Set the open and close durations and click "Save Preferences".
 *
 *   - You don't need to change the motor delays unless you need very precise positioning, like the semi-blind position.
 *     Even if you do need precise positioning, the default values should be good enough for most use cases.
 *
 *   - Open and close the shade fully, record the times, and set the open and close times in the preferences.
 *
 *   - After that, you can use the open/close/position commands as usual, and the driver will estimate the position
 *     based on the configured timings.
 *
 *   - Advanced config for more precise timings:
 *
 *     - Record a video of the shade opening and closing, and check the timestamps on a subtitle tool
 *       or another video analysis tool that shows precise frames timestamps (milliseconds).
 *
 *     - The motor delay is the time elapsed from when you hear the controller relay click, until you
 *       see a frame with the shade actually moving.
 *
 *     - The open/close time is the time elapsed from when the shade starts moving until it reaches the fully
 *       open/closed position.
 *
 * Source: https://github.com/TODO
 * Author: Eduardo Simioni
 *
 * 1.1.0 (2026-03-05) [Eduardo Simioni] - Added support for Sonoff MINI-ZBRBS, Turbo Mode and many other improvements.
 * 1.0.0 (2021-05-03) [Eduardo Simioni] - Initial version
 *
 * References:
 *   https://community.hubitat.com/t/any-way-to-programmatically-set-position-of-motorized-blinds/43092/16
 *   https://community.hubitat.com/t/cellular-blinds-with-zigbee-or-direct-hubitat-communication/62402/15
 *   https://community.hubitat.com/t/zigbee-cutain-module-ts130f/107907/26
 *   https://community.hubitat.com/t/tuya-zigbee-roller-shade-blind-motor-hubitat-issues/91223?page=7
 *   https://community.hubitat.com/t/release-zemismart-zigbee-blind-driver/67525/1
 *   https://raw.githubusercontent.com/amosyuen/hubitat-zemismart-zigbee/development/Zemismart%20Zigbee%20Blind.groovy
 */

import groovy.transform.Field
import java.math.RoundingMode

metadata {
    definition(name: 'Zigbee Window Shade/Blind (Timed)', namespace: 'edu', author: 'Eduardo Simioni') {
        capability 'Window Shade'

        command 'resetPosition', [[name: 'position*', type: 'NUMBER', constraints: ['NUMBER'], defaultValue : '50', range: '0..100', description: 'Changes only the driver state, no commands are sent to the device']]

        fingerprint model: 'TS130F', manufacturer: '_TZ3000_iaxvag8w', profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0102', outClusters: '', deviceJoinName: 'Zemismart Zigbee Curtain Module ZW-EC-01'
        fingerprint model: 'TS130F', manufacturer: '_TZ3000_femsaaua', profileId: '0104', endpointId: '01', inClusters: '0004,0005,0006,0102,E001,0000', outClusters: '0019,000A', deviceJoinName: 'LoraTap Zigbee Curtain Module SC-500-ZB'
        fingerprint model: 'MINI-ZBRBS', manufacturer: 'SONOFF', profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0007,0102,0B05,FC57,FC11', outClusters: '0003,0019', deviceJoinName: 'Sonoff MINI-ZBRBS Zigbee Roller Blind Shutter'
    }
    preferences {
        input(name: 'invertOpenClose', type: 'bool', title: 'Invert open/close commands', description: '', defaultValue: false, required: true)
        input(name: 'openTime', type: 'number', title: 'Time to open', description: 'In milliseconds', defaultValue: '10000', range: '1..100000', required: true, displayDuringSetup: true)
        input(name: 'closeTime', type: 'number', title: 'Time to close', description: 'In milliseconds', defaultValue: '10000', range: '1..100000', required: true, displayDuringSetup: true)
        input(name: 'semiBlindTime', type: 'number', title: 'Semi-blind position time', description: 'Time in ms from closed to semi-blind (cells closed) position. Triggered by setting position to 3%. Set to 0 to disable.', defaultValue: '0', range: '0..5000', required: true, displayDuringSetup: true)
        input(name: 'openStartDelay', type: 'number', title: 'Motor start delay (open)', description: 'Motor delay in ms after you hear the switch click', defaultValue: '-108', range: '-5000..5000', required: true, displayDuringSetup: true)
        input(name: 'closeStartDelay', type: 'number', title: 'Motor start delay (close)', description: 'Motor delay in ms after you hear the switch click', defaultValue: '186', range: '-5000..5000', required: true, displayDuringSetup: true)
        input(name: 'openCloseSafetyMargin', type: 'number', title: 'Open/Close safety margin', description: 'In milliseconds', defaultValue: '1000', range: '0..5000', required: true)
        input(name: 'turboMode', type: 'enum', title: 'Turbo Mode (Sonoff only)', description: 'Increases Zigbee radio power for better range.', defaultValue: '9', required: true, options: ['9':'Disabled', '20':'Enabled'])
        input(name: 'loggingLevel', type: 'enum', title: 'Logging level', description: '', defaultValue: '3', required: true, options: ['1':'Error', '2':'Warning', '3':'Info', '4':'Debug', '5':'Trace'])
    }
}

@Field static final CLUSTER_SHADE_BLIND    = 0x0102

// Custom Tuya commands for Zemismart and Loratap
@Field static final OUT_COMMAND_TUYA_CLOSE = 0x00
@Field static final OUT_COMMAND_TUYA_OPEN  = 0x01
@Field static final OUT_COMMAND_TUYA_PAUSE = 0x02

@Field static final IN_COMMAND_TUYA_CLOSE  = '00'
@Field static final IN_COMMAND_TUYA_OPEN   = '02'
@Field static final IN_COMMAND_TUYA_PAUSE  = '01'

// Standard ZCL commands for Sonoff MINI-ZBRBSs
@Field static final OUT_COMMAND_ZCL_OPEN   = 0x00 // Up/Open
@Field static final OUT_COMMAND_ZCL_CLOSE  = 0x01 // Down/Close
@Field static final OUT_COMMAND_ZCL_PAUSE  = 0x02 // Stop/Pause

@Field static final IN_COMMAND_ZCL_OPEN    = '00' // Up/Open
@Field static final IN_COMMAND_ZCL_CLOSE   = '01' // Down/Close
@Field static final IN_COMMAND_ZCL_PAUSE   = '02' // Stop/Pause

// Sonoff MINI-ZBRBS custom cluster and attributes for Turbo Mode
@Field static final CLUSTER_SONOFF_CUSTOM  = 0xFC11
@Field static final ATTR_TURBO_MODE        = 0x0012 // INT16, 9=Disabled, 20=Enabled

@Field static final FULLY_OPENED           = 100
@Field static final FULLY_CLOSED           = 0

@Field static final ZIGBEE_DELAY_LIMIT     = 150
// To be more precise on the positioning, when closer to the lower limit, first close fully (if target position is below threshold), then set the position
@Field static final INVERTED_THRESHOLD     = 5
// Semi-blind position: when this percentage is requested, uses semiBlindTime preference for precise positioning
@Field static final SEMI_BLIND_POSITION    = 3

///////////////////////////
// Message Parsing
///////////////////////////

def parse(String description) {
    logger('D', {"description ${description}"})
    if (description == null || (!description.startsWith('catchall:') && !description.startsWith('read attr -'))) {
        logger('W', {"parse: Unhandled description=${description}"})
        return null
    }
    Map descMap = zigbee.parseDescriptionAsMap(description)
    logger('D', {"descMap ${descMap}"})
    if (descMap && descMap.clusterInt == CLUSTER_SHADE_BLIND) {
        logger('D', 'Shade cluster command received')
        def command = getCommandFromDescMap(descMap)
        logger('I', {"Received command: ${command}"})
        if (command == getInCommandPause()) {
            pauseReceived()
        } else if (command == getInCommandClose() || command == getInCommandOpen()) {
            openCloseReceived(command == getInCommandOpen())
        }
    } else if (descMap && descMap.direction == '01' && descMap.data[0] == '02' && descMap.data[1] == '81') {
        logger('D', 'Pause command failed')
        pauseReceived()
    } else if (descMap && descMap.clusterInt == 0 && descMap.encoding == '20' && descMap.command == '01' && descMap.value == '03') {
        logger('I', 'Ping received')
    } else {
        logger('D', 'DID NOT PARSE UNKNOWN MESSAGE')
    }
}

String getCommandFromDescMap(Map descMap) {
    if (isTuya()) {
        return descMap.additionalAttrs ? descMap.additionalAttrs[0].value : getUnknownCommandDesc(descMap)
    } else {
        return descMap.data ? descMap.data[0] : getUnknownCommandDesc(descMap)
    }
}

String getUnknownCommandDesc(Map descMap) {
    return 'Unknown CL: ' + (descMap.cluster ? descMap.cluster : descMap.clusterId) + " CM: '$descMap.command'"
}

void openCloseReceived(boolean open) {
    calculateCommandDelay()
    logger('D', {(open ? 'open' : 'close') + ' received'})
    state.motionStartTime = getMovementChangeTime()
    state.pauseSchedule = 0
    sendEvent(name: 'windowShade', value: open ? 'opening' : 'closing')
    logger('D', {'windowShade set to ' + (open ? 'opening' : 'closing')})
    long timeToReach = calcTimeToReach(state.desiredPosition)
    setPauseSchedule(timeToReach)
    long safetyTimeout = timeToReach + 5000
    runInMillis(safetyTimeout, 'safetyTimeoutCheck')
}

void pauseReceived() {
    calculateCommandDelay()
    logger('I', 'Pause triggered')
    if (!isMoving()) {
        logger('D', 'Ignoring pause command... Not moving!')
        return
    }
    unschedule('safetyTimeoutCheck')
    state.pauseTime = getMovementChangeTime()
    state.pauseSchedule = 0
    updatePosition()
}

///////////////////////////
// Command Mapping
///////////////////////////

String getInCommandPause() {
    return isTuya() ? IN_COMMAND_TUYA_PAUSE : IN_COMMAND_ZCL_PAUSE
}

int getOutCommandPause() {
    return isTuya() ? OUT_COMMAND_TUYA_PAUSE : OUT_COMMAND_ZCL_PAUSE
}

int getOutCommandOpen() {
    if (isTuya()) {
        return settings.invertOpenClose ? OUT_COMMAND_TUYA_CLOSE : OUT_COMMAND_TUYA_OPEN
    } else {
        return settings.invertOpenClose ? OUT_COMMAND_ZCL_CLOSE : OUT_COMMAND_ZCL_OPEN
    }
}

int getOutCommandClose() {
    if (isTuya()) {
        return settings.invertOpenClose ? OUT_COMMAND_TUYA_OPEN : OUT_COMMAND_TUYA_CLOSE
    } else {
        return settings.invertOpenClose ? OUT_COMMAND_ZCL_OPEN : OUT_COMMAND_ZCL_CLOSE
    }
}

String getInCommandOpen() {
    if (isTuya()) {
        return settings.invertOpenClose ? IN_COMMAND_TUYA_CLOSE : IN_COMMAND_TUYA_OPEN
    } else {
        return settings.invertOpenClose ? IN_COMMAND_ZCL_CLOSE : IN_COMMAND_ZCL_OPEN
    }
}

String getInCommandClose() {
    if (isTuya()) {
        return settings.invertOpenClose ? IN_COMMAND_TUYA_OPEN : IN_COMMAND_TUYA_CLOSE
    } else {
        return settings.invertOpenClose ? IN_COMMAND_ZCL_OPEN : IN_COMMAND_ZCL_CLOSE
    }
}

///////////////////////////
// Driver Commands
///////////////////////////

void open() {
    logger('D', 'open() called')
    setPosition(FULLY_OPENED)
}

void close() {
    logger('D', 'close() called')
    setPosition(FULLY_CLOSED)
}

void startPositionChange(String direction) {
    logger('D', {"startPositionChange(${direction})"})
    setPosition(direction == 'up' ? FULLY_OPENED : FULLY_CLOSED)
}

void stopPositionChange() {
    logger('D', 'stopPositionChange()')
    unschedule()
    sendPauseCommand()
}

void setPosition(position) {
    if (position == null || position instanceof String) {
        logger('W', {"setPosition received an unexpected parameter (${position}), skipping"})
        return
    }
    if (isMoving()) {
        logger('D', 'Motor is still moving, pausing instead of setting position')
        stopPositionChange()
        return
    }
    def currentPos = getCurrentPosition()
    def isChange = Math.abs(position - currentPos) > 1
    logger('I', {"setPosition(${position}%) - Current position is ${currentPos}%" + getHumanPosition(currentPos) + (isChange ? '' : ' - Ignoring change to the same position (±1%)')})
    if (!isChange) {
        return
    }
    state.desiredPosition = position
    logger('D', {"direction is ${state.desiredPosition > currentPos ? 'up' : 'down'}"})
    if (state.desiredPosition > 0 && state.desiredPosition <= INVERTED_THRESHOLD && currentPos > INVERTED_THRESHOLD) {
        logger('D', 'Using inverted movement for accuracy')
        runInMillis(calcTimeToReach(0) + settings.openCloseSafetyMargin, 'setPosition', [data: state.desiredPosition])
        setPosition(0)
        return
    }
    if (isUpwards()) { sendOpenCommand() } else { sendCloseCommand() }
}

// Custom driver Command
void resetPosition(position) {
    if (position > 100 || position < 0) {
        logger('W', {"Reset position '${position}' invalid, must be between 0 and 100"})
        return
    }
    state.resetPosition = position
    resetState()
    updateWindowShade(position)
}

///////////////////////////
// Zigbee Communication
///////////////////////////

void sendOpenCommand() {
    registerSentCommand('open')
    sendZigbeeCommands(zigbee.command(CLUSTER_SHADE_BLIND, getOutCommandOpen()))
}

void sendCloseCommand() {
    registerSentCommand('close')
    sendZigbeeCommands(zigbee.command(CLUSTER_SHADE_BLIND, getOutCommandClose()))
}

void sendPauseCommand() {
    registerSentCommand('pause')
    long diff = state.commandSendTime - state.scheduledPauseTime
    logger('D', {"state.commandSendTime = ${state.commandSendTime}"})
    logger('D', {"state.scheduledPauseTime = ${state.scheduledPauseTime}"})
    logger('D', {"Diff from expected pause time = ${diff}ms"})
    sendZigbeeCommands(zigbee.command(CLUSTER_SHADE_BLIND, getOutCommandPause()))
}

void registerSentCommand(String name) {
    logger('D', {"<b>Sending zigbee command ${name}</b>"})
    state.commandSendTime = now()
    state.lastCommandSent = name
}

void setTurboMode(int val) {
    if (isSonoff()) {
        logger('I', {"setting Turbo Mode to: ${val == 20 ? 'Enabled' : 'Disabled'} (raw ${val})"})
        sendZigbeeCommands(zigbee.writeAttribute(CLUSTER_SONOFF_CUSTOM, ATTR_TURBO_MODE, DataType.INT16, val, [destEndpoint:0x01, mfgCode:0x1286], delay = 200))
    }
}


///////////////////////////
// Position Estimation
///////////////////////////

long calcTimeToReach(position) {
    // Semi-blind: use fixed time instead of percentage-based calculation
    if (position == SEMI_BLIND_POSITION && settings.semiBlindTime > 0) {
        long timeToReach = (long) settings.semiBlindTime + getOpenOrCloseStartDelay()
        logger('D', {"Semi-blind timeToReach position ${position} is ${timeToReach}ms"})
        return timeToReach
    }
    double distanceFactor = Math.abs(position - getCurrentPosition()) / 100
    long timeToReach = distanceFactor * getOpenOrCloseTime()
    timeToReach += getOpenOrCloseStartDelay()
    logger('D', {"timeToReach position ${position} is ${timeToReach}"})
    long safetyMargin = (position == FULLY_CLOSED || position == FULLY_OPENED ? settings.openCloseSafetyMargin : 0)
    logger('D', {"adding a safety margin of ${safetyMargin}"})
    return timeToReach + safetyMargin
}

void calculateCommandDelay() {
    def delay = (now() - state.commandSendTime) / 2
    if (delay > ZIGBEE_DELAY_LIMIT) {
        logger('W', {"Zigbee command delay was ${delay}ms, keeping last value: ${state.zigbeeCommandDelay}ms"})
    } else {
        state.zigbeeCommandDelay = delay
        logger('D', {"New Zigbee command delay is ${state.zigbeeCommandDelay}ms"})
    }
}

long getMovementChangeTime() {
    return now() - state.zigbeeCommandDelay
}

long getOpenOrCloseTime() {
    long time = isUpwards() ? settings.openTime : settings.closeTime
    logger('D', {(isUpwards() ? 'Open' : 'Close') + " time: ${time}"})
    return time
}

long getOpenOrCloseStartDelay() {
    long delay = isUpwards() ? settings.openStartDelay : settings.closeStartDelay
    logger('D', {(isUpwards() ? 'Open' : 'Close') + " delay: ${delay}"})
    return delay
}

///////////////////////////
// Pause Scheduling
///////////////////////////

// Without this workaround the pause schedule would have too much variation
// In regular operation it was ~25ms, and with inverted operation ~75ms, no idea why
void prePauseCommand() {
    long delay = state.scheduledPauseTime - now()
    long staleTolerance = getOpenOrCloseTime() + settings.openCloseSafetyMargin
    if (delay < -staleTolerance) {
        logger('W', {"pre-pause delay is ${delay}ms, well beyond movement time of ${staleTolerance}ms. Recovering state..."})
        recoverStaleState()
        return
    }
    if (delay <= 0) {
        logger('W', {"pre-pause delay not positive: ${delay}, changing to 50ms"})
        delay = 50
    }
    runInMillis(delay, 'sendPauseCommand')
    logger('D', 'Pre-pause scheduled a sendPauseCommand')
}

void setPauseSchedule(long time) {
    long pauseIn = time - ((state.zigbeeCommandDelay + getOpenOrCloseStartDelay()) * 2)
    if ((state.pauseSchedule == 0 || pauseIn < state.pauseSchedule) && pauseIn > 0) {
        state.pauseSchedule = pauseIn
        state.scheduledPauseTime = now() + pauseIn
        long runIn = state.pauseSchedule - 100
        if (runIn <= 0) {
            prePauseCommand()
        } else {
            runInMillis(runIn, 'prePauseCommand')
            logger('D', {"Scheduled a prePauseCommand() in ${state.pauseSchedule}ms"})
        }
    } else {
        logger('D', {"Ignoring pause schedule of ${pauseIn}, current pause schedule is ${state.pauseSchedule}"})
    }
}

void recoverStaleState() {
    unschedule('safetyTimeoutCheck')
    long recoveredPosition = isUpwards() ? FULLY_OPENED : FULLY_CLOSED
    logger('W', {"Stale state recovery: assuming device reached ${isUpwards() ? 'open' : 'closed'} limit (${recoveredPosition}%)"})
    state.pauseSchedule = 0
    updateWindowShade(recoveredPosition)
    sendPauseCommand()
}

void safetyTimeoutCheck() {
    if (isMoving()) {
        logger('W', 'Safety timeout: device still marked as moving after expected time. Recovering...')
        recoverStaleState()
    }
}

boolean isUpwards() {
    return state.desiredPosition > getCurrentPosition()
}

boolean isMoving() {
    return device.currentValue('windowShade') in ['opening', 'closing']
}

void updatePosition() {
    long elapsedTime = state.pauseTime - state.motionStartTime
    logger('D', {"Elapsed time: ${elapsedTime}"})
    logger('D', {"isUpwards() = ${isUpwards()}"})
    double positionsMoved = (isUpwards() ? 1 : -1) * (elapsedTime / getOpenOrCloseTime()) * 100
    logger('D', {"Positions moved: ${positionsMoved}"})
    def newPos = getCurrentPosition() + new BigDecimal(positionsMoved).setScale(1, RoundingMode.HALF_UP)
    if (newPos > FULLY_OPENED) {
        logger('D', {"Limiting position level to ${FULLY_OPENED}"})
        newPos = FULLY_OPENED
    } else if (newPos < FULLY_CLOSED) {
        logger('D', {"Limiting position level to ${FULLY_CLOSED}"})
        newPos = FULLY_CLOSED
    }
    updateWindowShade(newPos)
}

void updateWindowShade(pos) {
    sendEvent(name: 'position', value: pos)
    logger('I', {"New position is ${pos}%"})
    if (pos > FULLY_CLOSED && pos < FULLY_OPENED) {
        sendEvent(name: 'windowShade', value: 'partially open')
    } else {
        sendEvent(name: 'windowShade', value: pos == FULLY_CLOSED ? 'closed' : 'open')
    }
}

Number getCurrentPosition() {
    return device.currentValue('position')
}

///////////////////////////
// Lifecycle
///////////////////////////

void installed() {
    logger('I', 'Installed...')
    resetState()
}

void updated() {
    logger('I', 'Preferences updated...')
    setTurboMode(safeToInt(settings.turboMode, 9))
}

void initialize() {
    logger('I', 'Initialized...')
    resetState()
}

void resetState() {
    unschedule()
    if (state.resetPosition == null) {
        state.resetPosition = 50
    }
    state.motionStartTime = 0
    state.pauseTime = 0
    state.pauseSchedule = 0
    state.scheduledPauseTime = 0
    state.commandSendTime = 0
    state.lastCommandSent = 'none'
    state.desiredPosition = state.resetPosition
    state.zigbeeCommandDelay = 50
    if (getCurrentPosition() == null) {
        updateWindowShade(state.resetPosition)
    }
}

///////////////////////////
// Utilities
///////////////////////////

boolean isTuya() {
    return !isSonoff()
}

boolean isSonoff() {
    return device.getDataValue('manufacturer') == 'SONOFF'
}

Integer safeToInt(val, Integer defaultVal=0) {
    return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

String getHumanPosition(pos) {
    if (pos == FULLY_CLOSED) {
        return ' (Closed)'
    } else if (pos == FULLY_OPENED) {
        return ' (Open)'
    } else {
        return ' (Partially open)'
    }
}

void sendZigbeeCommands(List<String> cmd) {
    logger('D', {"<b>sendZigbeeCommands</b> (cmd=$cmd)"})
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
        allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
    }
    sendHubCommand(allActions)
}

private void logger(level, message) {
    int configuredLevel = safeToInt(settings.loggingLevel, 3)
    switch (level) {
        case 'E': if (configuredLevel >= 1) { log.error(getLogMessage(message)) }; break
        case 'W': if (configuredLevel >= 2) { log.warn(getLogMessage(message)) }; break
        case 'I': if (configuredLevel >= 3) { log.info(getLogMessage(message)) }; break
        case 'D': if (configuredLevel >= 4) { log.debug(getLogMessage(message)) }; break
        case 'T': if (configuredLevel >= 5) { log.trace(getLogMessage(message)) }; break
    }
}

private String getLogMessage(message) {
    def text = (message instanceof Closure) ? message() : message
    return "${device.displayName}: ${text}"
}