/*
Developed by Zachary Selk at the University of Alberta (zrselk@gmail.com)
and Fr. Br. George (frbrgeorge@altlinux.org)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
*/

package rars.tools;

import rars.Globals;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.MemoryAccessNotice;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.InterruptController;
import rars.riscv.hardware.ControlAndStatusRegisterFile;
import rars.riscv.hardware.AddressErrorException;

import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;


/**
 * A RARS headless tool used to implement a timing module and timer inturrpts.
 **/
public class HeadlessTimer {
    private static String heading = "Headless Timer";
    private static String version = "Version 1.0 (Fr. Br. George)";
    private static final int TIME_ADDRESS = Memory.memoryMapBaseAddress + 0x18;
    private static final int TIME_CMP_ADDRESS = Memory.memoryMapBaseAddress + 0x20;

    // Internal time values
    private static long time = 0L;      // The current time of the program (starting from 0)
    private static long startTime = 0L; // Tmp unix time used to keep track of how much time has passed
    private static long savedTime = 0L; // Accumulates time as we pause/play the timer

    // Timing threads
    private static TimeCmpDaemon timeCmp = null; // Watches for changes made to timecmp
    private Timer timer = new Timer();
    private Tick tick = new Tick(); // Runs every millisecond to decide if a timer inturrupt should be raised

    // Internal timing flags
    private static boolean updateTime = false;    // Controls when time progresses (for pausing)
    private static boolean running = false;       // true while tick thread is running

    // A daemon that watches the timecmp MMIO for any changes
    private void startTimeCmpDaemon() {
        if (timeCmp == null) {
            timeCmp = new TimeCmpDaemon();
        }
    }

    /***************************  Timer controls  *****************************/

    public void start() {
        if (!running) {
            startTimeCmpDaemon();
            // Start a timer that checks to see if a timer interupt needs to be raised
            // every millisecond
            timer.schedule(tick, 0, 1);
            running = true;
        }
    }

    public void play() {
        // Gaurd against multiple plays
        if (!updateTime) {
            updateTime = true;
            startTime = System.currentTimeMillis();
        }

    }

    public void pause() {
        // Gaurd against multiple pauses
        if (updateTime) {
            updateTime = false;
            time = savedTime + System.currentTimeMillis() - startTime;
            savedTime = time;
        }
    }

    // Reset all of our counters to their default values
    protected void reset() {
        time = 0L;
        savedTime = 0L;
        startTime = System.currentTimeMillis();
        tick.updateTimecmp = true;
        tick.reset();
    }

    // Shutdown the timer (note that we keep the TimeCmpDaemon running)
    public void stop() {
        updateTime = false;
        timer.cancel();
        running = false;
        reset();
    }


    /*****************************  Timer Classes  *****************************/

    // Watches for changes made to the timecmp MMIO
    public class TimeCmpDaemon implements Observer {
        public boolean postInterrupt = false;
        public long value = 0L; // Holds the most recent value of timecmp writen to the MMIO

        public TimeCmpDaemon() {
            addAsObserver();
        }

        public void addAsObserver() {
            try {
                Globals.memory.addObserver(this, TIME_CMP_ADDRESS, TIME_CMP_ADDRESS+8);
            } catch (AddressErrorException aee) {
                System.out.println("Error while adding observer in Headless Timer");
                System.exit(1);
            }
        }

        public void update(Observable ressource, Object accessNotice) {
            MemoryAccessNotice notice = (MemoryAccessNotice) accessNotice;
            int accessType = ((AccessNotice)accessNotice).getAccessType();
            // If is was a WRITE operation
            if (accessType == 1) {
                int address = notice.getAddress();
                int value = notice.getValue();

                // Check what word was changed, then update the corrisponding information
                if (address == TIME_CMP_ADDRESS) {
                    this.value = ((this.value >> 32) << 32) + value;
                    postInterrupt = true; // timecmp was writen to
                }
                else if (address == TIME_CMP_ADDRESS+4) {
                    this.value = (this.value & 0xFFFFFFFF) + (((long)value) << 32);
                    postInterrupt = true; // timecmp was writen to
                }
            }
        }
    }

    // Runs every millisecond to decide if a timer inturrupt should be raised
    private class Tick extends TimerTask {
        public volatile boolean updateTimecmp = true;

        public void run() {
	    // If the tool is not paused
	    if (updateTime) {
		// time is the difference between the last time we started the time and now, plus
		// our time accumulator
		time = savedTime + System.currentTimeMillis() - startTime;

		// Write the lower and upper words of the time MMIO respectivly
		updateMMIOControlAndData(TIME_ADDRESS, (int)(time & 0xFFFFFFFF));
		updateMMIOControlAndData(TIME_ADDRESS+4, (int)(time >> 32));

		// The logic for if a timer interrupt should be raised
		// Note: if either the UTIP bit in the uie CSR or the UIE bit in the ustatus CSR
		//      are zero then this interrupt will be stopped further on in the pipeline
		if (time >= timeCmp.value && timeCmp.postInterrupt && bitsEnabled()) {
		    InterruptController.registerTimerInterrupt(ControlAndStatusRegisterFile.TIMER_INTERRUPT);
		    timeCmp.postInterrupt = false; // Wait for timecmp to be writen to again
		}
	    }
        }

        // Checks the control bits to see if user-level timer inturrupts are enabled
        private boolean bitsEnabled() {
            boolean utip = (ControlAndStatusRegisterFile.getValue("uie") & 0x10) == 0x10;
            boolean uie = (ControlAndStatusRegisterFile.getValue("ustatus") & 0x1) == 0x1;

            return (utip && uie);
        }

        // Set time MMIO to zero
        public void reset() {
            updateMMIOControlAndData(TIME_ADDRESS, 0);
            updateMMIOControlAndData(TIME_ADDRESS+4, 0);
        }
    }

    // Writes a word to a virtual memory address
    private synchronized void updateMMIOControlAndData(int dataAddr, int dataValue) {
        Globals.memoryAndRegistersLock.lock();
        try {
            try {
                Globals.memory.setRawWord(dataAddr, dataValue);
            } catch (AddressErrorException aee) {
                System.out.println("Tool author specified incorrect MMIO address!" + aee);
                System.exit(0);
            }
        } finally {
            Globals.memoryAndRegistersLock.unlock();
        }
    }

}
