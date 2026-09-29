package windowshadetimed

import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Pure-logic tests for the "Zigbee Window Shade/Blind (Timed)" driver.
 *
 * This driver has no position feedback — it ESTIMATES position from movement time, so the two
 * kernels of that estimation are exactly where a regression would hide. They were extracted as
 * pure methods:
 *
 *   - estimatePosition(): the position reached after moving elapsedMs, with 0.1% HALF_UP rounding
 *                         and clamping to [0, 100]. (extracted from updatePosition)
 *   - calcTravelTime():   how long to run the motor to cover a % distance, plus the motor delay.
 *                         (extracted from calcTimeToReach)
 *
 * Also covers safeToInt(), the settings parser, which was already pure (no extraction needed) — to
 * show the spec covers pre-existing pure helpers too, not only freshly-extracted ones.
 *
 * Loaded as-is via hubitat_ci with the shared flag set — see GenericZigbeeRepeaterSpec / the root
 * CLAUDE.md for why each flag is there.
 */
class WindowShadeTimedSpec extends Specification {

    static final File DRIVER = new File(
            System.getProperty('driversRoot'),
            'hubitat-window-shade-timed/window-shade-timed.groovy')

    @Shared
    def driver = new HubitatDeviceSandbox(DRIVER).run(validationFlags: [
            Flags.DontRunScript,
            Flags.DontRestrictGroovy,
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowReadingNonInputSettings,
    ])

    @Unroll
    def "estimatePosition: from #from%, #elapsed ms of #travel ms travel, upwards=#up -> #expected%"() {
        expect:
        driver.estimatePosition(from, elapsed, travel, up) == expected

        where:
        from | elapsed | travel | up    || expected
        0    | 5000    | 10000  | true  || 50.0    // half the travel time, opening -> 50%
        100  | 2500    | 10000  | false || 75.0    // a quarter, closing from fully open
        0    | 3333    | 10000  | true  || 33.3    // rounds the delta to 0.1%
        80   | 10000   | 10000  | true  || 100     // clamp high (raw would be 180)
        20   | 10000   | 10000  | false || 0       // clamp low (raw would be -80)
        50   | 0       | 10000  | true  || 50.0    // no elapsed time -> no change
    }

    @Unroll
    def "calcTravelTime: #from% -> #to% over #travel ms (delay #delay) = #expected ms"() {
        expect:
        driver.calcTravelTime(from, to, travel, delay) == expected

        where:
        from | to  | travel | delay || expected
        0    | 100 | 10000  | 0     || 10000   // full travel
        0    | 50  | 10000  | 0     || 5000    // half travel
        100  | 50  | 10000  | 0     || 5000    // distance is absolute (direction-agnostic)
        0    | 50  | 10000  | 186   || 5186    // + close start delay
        0    | 100 | 10000  | -108  || 9892    // + (negative) open start delay
        50   | 50  | 10000  | 0     || 0       // no distance -> just the (here zero) delay
    }

    @Unroll
    def "safeToInt(#val, #dflt) == #expected"() {
        expect:
        driver.safeToInt(val, dflt) == expected

        where:
        val   | dflt || expected
        '9'   | 0    || 9
        '20'  | 9    || 20
        'abc' | 9    || 9       // non-integer -> default
        null  | 3    || 3       // null -> default
    }

    @Unroll
    def "turboWriteStatus: cluster #cluster, zclCmd #cmd, data #data -> #expected"() {
        expect:
        driver.turboWriteStatus([clusterInt: cluster, command: cmd, data: data]) == expected

        where:
        cluster | cmd  | data               || expected
        0xFC11  | '04' | ['00']             || 0x00    // Write Attributes Response: every write succeeded
        0xFC11  | '04' | ['86', '12', '00'] || 0x86    // UNSUPPORTED_ATTRIBUTE for 0x0012 (MINI-ZBRBS, mfg-specific write, 29/09/2026)
        0xFC11  | '0B' | ['02', '00']       || null    // a Default Response, not a Write Attributes Response
        0x0102  | '04' | ['00']             || null    // another cluster
        0xFC11  | '04' | []                 || null    // malformed: no status byte
    }
}
