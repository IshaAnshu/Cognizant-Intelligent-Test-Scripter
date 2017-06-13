/*
 * Copyright 2014 - 2017 Cognizant Technology Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cognizant.cognizantits.engine.core;

import com.cognizant.cognizantits.datalib.component.Project;
import com.cognizant.cognizantits.datalib.component.Scenario;
import com.cognizant.cognizantits.datalib.component.TestCase;
import com.cognizant.cognizantits.engine.constants.SystemDefaults;
import com.cognizant.cognizantits.engine.drivers.SeleniumDriver;
import com.cognizant.cognizantits.engine.execution.data.Parameter;
import com.cognizant.cognizantits.engine.execution.data.UserDataAccess;
import com.cognizant.cognizantits.engine.execution.exception.DriverClosedException;
import com.cognizant.cognizantits.engine.execution.exception.ForcedException;
import com.cognizant.cognizantits.engine.execution.exception.UnCaughtException;
import com.cognizant.cognizantits.engine.execution.exception.UnKnownError;
import com.cognizant.cognizantits.engine.execution.exception.data.DataNotFoundException;
import com.cognizant.cognizantits.engine.execution.exception.element.ElementException;
import com.cognizant.cognizantits.engine.execution.run.TestCaseRunner;
import com.cognizant.cognizantits.engine.reporting.TestCaseReport;
import com.cognizant.cognizantits.engine.reporting.util.DateTimeUtils;
import com.cognizant.cognizantits.engine.support.Status;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Task implements Runnable {

    TestCaseReport report;
    RunContext runContext;
    SeleniumDriver seleniumDriver;
    DateTimeUtils runTime;
    UserDataAccess userData;
    TestCaseRunner runner;

    public Task(RunContext RC) {
        runContext = RC;
    }

    public Project project() {
        return Control.exe.getProject();
    }

    @Override
    public void run() {
        runTime = new DateTimeUtils();
        report = new TestCaseReport();
        TestCase stc = getTestCase();
        if (stc != null) {
            runner = new TestCaseRunner(Control.exe, stc);
        } else {
            runner = new TestCaseRunner(Control.exe, runContext.Scenario,
                    runContext.TestCase);
        }
        report.createReport(runContext, DateTimeUtils.DateTimeNow());

        int iter = 1;
        if (RunManager.getGlobalSettings().isTestRun()) {
            runner.setMaxIter(1);
        } else {
            runner.setMaxIter(Parameter.resolveMaxIter(runContext.Iteration));
            iter = Parameter.resolveStartIter(runContext.Iteration);
        }

        while (!SystemDefaults.stopExecution.get() && iter <= runner.getMaxIter()) {
            try {
                System.out.println("Running Iteration " + iter);
                runIteration(iter++);
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, ex.getMessage(), ex);
            }
        }

        if (report != null) {
            Status s = report.finalizeReport();
            Control.ReportManager.updateTestCaseResults(runContext, report, s, runTime.timeRun());
            SystemDefaults.reportComplete.set(false);
        }

    }

    private TestCase getTestCase() {
        try {
            Scenario scn = project().getScenarioByName(runContext.Scenario);
            if (scn != null) {
                TestCase stc = scn.getTestCaseByName(runContext.TestCase);
                if (stc != null) {
                    return stc;
                } else {
                    LOG.log(Level.WARNING, "Testcase [{0}] not found", runContext.Scenario);
                }
            } else {
                LOG.log(Level.WARNING, "Scenario [{0}] not found", runContext.Scenario);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to load TestaCase", ex);
        }
        return null;
    }
    private static final Logger LOG = Logger.getLogger(Task.class.getName());

    public boolean runIteration(int iter) {
        boolean success = false;
        CommandControl cc = null;
        seleniumDriver = getSeDriver();
        try {
            SystemDefaults.reportComplete.set(true);
            report.startIteration(iter);
            launchBrowser();
            SystemDefaults.stopCurrentIteration.set(false);
            cc = createControl();
            runner.run(cc, iter);
            success = true;
        } catch (DriverClosedException dex) {
            Logger.getLogger(Task.class.getName()).log(Level.SEVERE, dex.getMessage(), dex);
            report.updateTestLog("DriverClosedException", dex.getMessage(), Status.FAILNS);
        } catch (ForcedException ex) {
            onError(ex, ex.getName(), ex.getMessage(), Status.FAIL);
        } catch (ElementException ex) {
            onError(ex, (cc != null ? cc.ObjectName : "Element Exception"),
                    ex.getMessage(), Status.FAIL);
        } catch (DataNotFoundException ex) {
            onError(ex, "DataNotFound", ex.toString());
        } catch (UnCaughtException ex) {
            onError(ex, "Unhandled Error", ex.getMessage());
        } catch (UnKnownError ex) {
            Logger.getLogger(Task.class.getName()).log(Level.SEVERE, ex.getMessage(), ex);
            onError(ex, "Error", ex.getMessage());
        } catch (Throwable ex) {
            onError(ex, "Error", ex.getMessage());
        } finally {
            if (seleniumDriver != null && !Control.exe.getExecSettings().getRunSettings().useExistingDriver()) {
                try {
                    seleniumDriver.closeBrowser();
                } catch (Exception ex) {
                    System.out.println("Driver Closed Unexpectedly");
                    onError(ex, "Driver Error", ex.getMessage());
                    LOG.log(Level.SEVERE, ex.getMessage(), ex);
                }
            }
            report.endIteration(iter);
        }
        return success;
    }

    private void launchBrowser() throws UnCaughtException {
        if (!Control.exe.getExecSettings().getRunSettings().useExistingDriver() || seleniumDriver.driver == null) {
            seleniumDriver.launchDriver(runContext);
        }
        report.setDriver(seleniumDriver);
    }

    private CommandControl createControl() {
        return new CommandControl(seleniumDriver, report) {
            @Override
            public void execute(String com, int sub) {
                runner.runTestCase(com, sub);
            }

            @Override
            public void executeAction(String action) {
                runner.runAction(action);
            }

            @Override
            public Object context() {
                return runner;
            }
        };
    }

    private void onError(Throwable ex, String err, String desc) {
        onError(ex, err, desc, Status.DEBUG);
    }

    private void onError(Throwable ex, String err, String desc, Status s) {
        if (ex != null) {
            LOG.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (report != null) {
            report.updateTestLog(err, desc
                    + System.lineSeparator() + "[Breaking execution!]", s);
        }
    }

    private SeleniumDriver getSeDriver() {
        SeleniumDriver seDriver;
        if (!Control.exe.getExecSettings().getRunSettings().useExistingDriver()
                || Control.getSeDriver() == null) {
            seDriver = new SeleniumDriver();
            Control.setSeDriver(seDriver);
        } else {
            seDriver = Control.getSeDriver();
        }
        return seDriver;
    }

}
