package com.alphatica.genotick.breeder;

import com.alphatica.genotick.data.ColumnAccessMergeStrategy;
import com.alphatica.genotick.data.SimpleColumnAccessMergeStrategy;
import com.alphatica.genotick.genotick.RandomGenerator;
import com.alphatica.genotick.genotick.WeightCalculator;
import com.alphatica.genotick.instructions.Instruction;
import com.alphatica.genotick.instructions.InstructionList;
import com.alphatica.genotick.instructions.TerminateInstructionList;
import com.alphatica.genotick.mutator.Mutator;
import com.alphatica.genotick.population.Population;
import com.alphatica.genotick.population.Robot;
import com.alphatica.genotick.population.RobotInfo;
import com.alphatica.genotick.population.RobotSettings;
import com.alphatica.genotick.ui.UserOutput;
import com.alphatica.genotick.utility.ParallelTasks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import static com.alphatica.genotick.utility.Assert.gassert;

public class SimpleBreeder implements RobotBreeder {
    private BreederSettings settings;
    private Mutator mutator;
    private RandomGenerator random;
    private final WeightCalculator weightCalculator;
    private final ColumnAccessMergeStrategy columnAccessMergeStrategy;
    private final UserOutput output;

    private SimpleBreeder(UserOutput output) {
        this.weightCalculator = new WeightCalculator();
        this.columnAccessMergeStrategy = new SimpleColumnAccessMergeStrategy();
        this.output = output;
    }
    
    public static RobotBreeder getInstance(UserOutput output) {
        return new SimpleBreeder(output);
    }

    @Override
    public void breedPopulation(final Population population, final List<RobotInfo> list) {
        if (population.hasSpaceToBreed()) {
            int before = population.getSize(), after;
            addRequiredRandomRobots(population);
            after = population.getSize();
            output.debugMessage("requiredRandomRobots=" + (after - before));
            before = after;
            breedPopulationFromParents(population, list);
            after = population.getSize();
            output.debugMessage("breededRobots=" + (after - before));
            before = after;
            addOptionalRandomRobots(population);
            after = population.getSize();
            output.debugMessage("optionalRandomRobots=" + (after - before));
            output.debugMessage("totalRobots=" + after);
        }
    }

    private void addOptionalRandomRobots(Population population) {
        int count = population.getDesiredSize() - population.getSize();
        if (count > 0) {
            fillWithRobots(count, population);
        }
    }

    private void addRequiredRandomRobots(Population population) {
        if (settings.randomRobots > 0) {
            int count = (int) Math.round(settings.randomRobots * population.getDesiredSize());
            fillWithRobots(count, population);
        }
    }

    private void fillWithRobotsSync(int count, Population population) {
        for (int i = 0; i < count; i++) {
            createNewRobot(population);
        }
    }

    private void fillWithRobots(int count, Population population) {
        	if(count < 32 || random.getSeed() != 0) {
        	    fillWithRobotsSync(count, population);
            return;
        	} else {
        	    ParallelTasks.parallelNumberedTask(count, (subCount) -> fillWithRobotsSync(subCount, population));
        	} 
    }

    private void createNewRobot(Population population) {
        final RobotSettings robotSettings = new RobotSettings(settings, weightCalculator);
        final Robot robot = Robot.createEmptyRobot(robotSettings, columnAccessMergeStrategy, random);
        mutator.setColumnAccess(robot.getColumnAccess());
        final int maximumRobotInstructionCount = settings.maximumRobotInstructions - settings.minimumRobotInstructions;
        int instructionCount = settings.minimumRobotInstructions + Math.abs(mutator.getNextInt() % maximumRobotInstructionCount);
        final InstructionList main = robot.getMainFunction();
        while (--instructionCount >= 0) {
            addInstructionToMain(main);
        }
        population.saveRobot(robot);
    }

    private void addInstructionToMain(InstructionList main) {
        Instruction instruction = mutator.getRandomInstruction();
        instruction.mutate(mutator);
        main.addInstruction(instruction);
    }

    private void breedPopulationFromParents(Population population, List<RobotInfo> originalList) {
        List<RobotInfo> robotInfos = new ArrayList<>(originalList);
        removeNotAllowedRobots(robotInfos);
        breedPopulationFromList(population, robotInfos);
    }

    private void removeNotAllowedRobots(List<RobotInfo> robotInfos) {
        robotInfos.removeIf(robotInfo -> !robotInfo.canBeParent(settings.minimumOutcomesToAllowBreeding, settings.minimumOutcomesBetweenBreeding));
    }

    private void breedPopulationFromList(Population population, List<RobotInfo> list) {
        list.sort(Comparator.comparing(RobotInfo::getScore));
        while (population.hasSpaceToBreed()) {
            boolean direction = random.nextBoolean();
            Robot parent1 = getPossibleParent(population, list, direction);
            Robot parent2 = getPossibleParent(population, list, direction);
            if (parent1 == null || parent2 == null)
                break;
            RobotSettings robotSettings = new RobotSettings(settings, weightCalculator);
            Robot child = Robot.createEmptyRobot(robotSettings, columnAccessMergeStrategy, random);
            makeChild(parent1, parent2, child);
            population.saveRobot(child);
            parent1.increaseChildren();
            population.saveRobot(parent1);
            parent2.increaseChildren();
            population.saveRobot(parent2);
        }
    }

    private void makeChild(Robot parent1, Robot parent2, Robot child) {
        double weight = calculateWeightForChild(parent1, parent2);
        child.setInheritedWeight(weight);
        child.setColumnAccess(columnAccessMergeStrategy.merge(parent1.getColumnAccess(), parent2.getColumnAccess()));
        mutator.setColumnAccess(child.getColumnAccess());
        InstructionList instructionList = mixMainInstructionLists(parent1, parent2);
        child.setMainInstructionList(instructionList);
    }

    private double calculateWeightForChild(final Robot parent1, final Robot parent2) {
        double weight = 0.0;
        if (settings.inheritedWeightPercent > 0.0) {
            switch (settings.inheritedWeightMode) {
                case PARENTS: weight = getMeanEarnedWeight(parent1, parent2) * settings.inheritedWeightPercent; break;
                case ANCESTORS: weight = getMeanInheritedWeight(parent1, parent2) + getMeanEarnedWeight(parent1, parent2) * settings.inheritedWeightPercent; break;
                case ANCESTORS_LOG: weight = getMeanWeight(parent1, parent2) * settings.inheritedWeightPercent; break;
            }
        }
        return weight;
    }
    
    private double getMeanEarnedWeight(final Robot parent1, final Robot parent2) {
        return (parent1.getEarnedWeight() + parent2.getEarnedWeight()) * 0.5;
    }
    
    private double getMeanInheritedWeight(final Robot parent1, final Robot parent2) {
        return (parent1.getInheritedWeight() + parent2.getInheritedWeight()) * 0.5;
    }
    
    private double getMeanWeight(final Robot parent1, final Robot parent2) {
        return (parent1.getWeight() + parent2.getWeight()) * 0.5;
    }

    private InstructionList mixMainInstructionLists(Robot parent1, Robot parent2) {
        InstructionList source1 = parent1.getMainFunction();
        InstructionList source2 = parent2.getMainFunction();
        return blendInstructionLists(source1, source2);
    }

    /*
    This potentially will make robots gradually shorter.
    Let's say that list1.size == 4 and list2.size == 2. Average length is 3.
    Then, break1 will be between <0,3> and break2 <0,1>
    All possible lengths for new InstructionList will be: 0,1,2,3,1,2,3,4 with equal probability.
    Average length is 2.
    For higher numbers this change isn't so dramatic but may add up after many populations.
     */
    private InstructionList blendInstructionLists(InstructionList list1, InstructionList list2) {
        InstructionList instructionList = InstructionList.create(random, settings.minimumRobotVariables, settings.maximumRobotVariables);
        int break1 = getBreakPoint(list1);
        int break2 = getBreakPoint(list2);
        copyBlock(instructionList, list1, 0, break1);
        copyBlock(instructionList, list2, break2, list2.getInstructionCount());
        return instructionList;
    }

    private int getBreakPoint(InstructionList list) {
        int size = list.getInstructionCount();
        if (size == 0)
            return 0;
        else
            return Math.abs(mutator.getNextInt() % size);
    }

    private void copyBlock(InstructionList destination, InstructionList source, int start, int stop) {
        gassert(start <= stop, String.format("start > stop %d %d", start, stop));
        for (int i = start; i <= stop; i++) {
            Instruction instruction = source.getInstruction(i).copy();
            if(instruction instanceof TerminateInstructionList) {
                break;
            }
            addInstructionToInstructionList(instruction, destination);
        }
    }

    private void addInstructionToInstructionList(Instruction instruction, InstructionList instructionList) {
        if (mutator.skipNextInstruction()) {
            return;
        }
        possiblyAddNewInstruction(instructionList);
        possiblyMutateInstruction(instruction);
        instructionList.addInstruction(instruction);
    }

    private void possiblyMutateInstruction(Instruction instruction) {
        if (mutator.getAllowInstructionMutation()) {
            instruction.mutate(mutator);
        }
    }

    private void possiblyAddNewInstruction(InstructionList instructionList) {
        if (mutator.getAllowNewInstruction()) {
            Instruction newInstruction = mutator.getRandomInstruction();
            instructionList.addInstruction(newInstruction);
        }
    }

    private Robot getPossibleParent(Population population, List<RobotInfo> list, boolean direction) {
        double totalWeight = sumTotalScore(list, direction);
        double target = totalWeight * mutator.getNextDouble();
        double scoreSoFar = 0;
        Iterator<RobotInfo> iterator = list.iterator();
        while (iterator.hasNext()) {
            RobotInfo robotInfo = iterator.next();
            if((direction == true && robotInfo.getWeight() < 0.0) || (direction == false && robotInfo.getWeight() >= 0.0)) {
                continue;
            }
            scoreSoFar += robotInfo.getScore();
            if (scoreSoFar >= target) {
                iterator.remove();
                return population.getRobot(robotInfo.getName());
            }
        }
        return null;
    }

    private double sumTotalScore(List<RobotInfo> list, boolean direction) {
        double score = 0;
        for (RobotInfo robotInfo : list) {
            if((direction == true && robotInfo.getWeight() >= 0.0) || (direction == false && robotInfo.getWeight() < 0.0)) {
                score += robotInfo.getScore();
            }
        }
        return score;
    }

    @Override
    public void setSettings(BreederSettings breederSettings, Mutator mutator) {
        this.settings = breederSettings;
        this.mutator = mutator;
        this.random = RandomGenerator.create(breederSettings.randomSeed);
        this.weightCalculator.setWeightMode(breederSettings.weightMode);
        this.weightCalculator.setWeightExponent(breederSettings.weightExponent);
    }
}

