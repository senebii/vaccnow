package com.assessment.vaccination.domain;

import com.assessment.common.ErrorCode;
import com.assessment.common.PreconditionException;
import com.assessment.config.AppProperties;
import com.assessment.vaccination.domain.entity.*;
import com.assessment.vaccination.domain.model.VaccinationScheduleReport;
import com.assessment.vaccination.domain.model.VaccinationScheduleReportModel;
import com.assessment.vaccination.domain.repository.*;
import com.assessment.vaccination.dto.VaccinationScheduleRequestDto;
import lombok.AllArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

@AllArgsConstructor
@Service
public class VaccinationService {

    private final BranchRepository branchRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final VaccineRepository vaccineRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CustomerRepository customerRepository;
    private final VaccinationScheduleRepository vaccinationScheduleRepository;
    private final JavaMailSender mailSender;
    private final AppProperties appProperties;
    private final ReportGenerator reportGenerator;

    /**
     *  Get list of branches
     */
    public List<Branch> getBranches(){
        return branchRepository.findAll();
    }

    /**
     *  Get list of vaccines given the branch code
     */
    public List<Vaccine> getVaccinesByBranchCode(String branchCode){
        Branch branch =
                branchRepository
                        .findByBranchCode(branchCode)
                        .orElseThrow(() -> new PreconditionException(ErrorCode.INTERNAL_SERVER_ERROR));
        return branch.getVaccines();
    }

    /**
     *  Get list of available time slots given a branch and a date. Occupied time slots are excluded.
     */
    public List<TimeSlot> getAvailableTimeSlots(String branchCode, LocalDate date){
        List<TimeSlot> timeSlots = timeSlotRepository.findAll();
        List<VaccinationSchedule> schedules =
                    vaccinationScheduleRepository.findByScheduleDateAndBranchCode(date, branchCode);

        return timeSlots.stream()
                    .filter(timeSlot ->
                            schedules
                                    .stream()
                                    .noneMatch(sched -> sched.getTimeSlot().getTimeSlotId().equals(timeSlot.getTimeSlotId())))
                    .collect(toList());
    }

    public List<PaymentMethod> getPaymentMethods(){
        return paymentMethodRepository.findAll();
    }

    /**
     *  Create Schedule Report
     */
    public VaccinationScheduleReport getScheduleReport(LocalDate fromDate,
                                                       LocalDate toDate,
                                                       String branchCode,
                                                       Boolean applied,
                                                       Boolean confirmed){
        List<VaccinationSchedule> schedules =
                vaccinationScheduleRepository.findSchedule(fromDate, toDate, branchCode, applied, confirmed);

        VaccinationScheduleReportModel model =
                VaccinationScheduleReportModel
                        .builder()
                        .fromDate(Optional.ofNullable(fromDate).map(LocalDate::toString).orElse(""))
                        .toDate(Optional.ofNullable(toDate).map(LocalDate::toString).orElse(""))
                        .schedules(schedules)
                        .build();

        byte[] reportInBytes = reportGenerator.createPdf(appProperties.getScheduleReportTemplate(), model);
        return new VaccinationScheduleReport(Base64.getEncoder().encodeToString(reportInBytes));
    }

    /**
     *  Schedule for a vaccination.
     */
    public VaccinationSchedule scheduleVaccination(VaccinationScheduleRequestDto scheduleRequest){
        Branch branch =
                branchRepository
                    .findByBranchCode(scheduleRequest.getBranchCode())
                    .orElseThrow(() -> new PreconditionException(ErrorCode.INVALID_BRANCH_CODE));
        Vaccine vaccine =
                vaccineRepository
                    .findById(scheduleRequest.getVaccineCode())
                    .orElseThrow(() -> new PreconditionException(ErrorCode.INVALID_VACCINE_CODE));


        PaymentMethod paymentMethod =
                paymentMethodRepository
                    .findById(scheduleRequest.getPaymentMethodId())
                    .orElseThrow(() -> new PreconditionException(ErrorCode.INVALID_PAYMENT_METHOD));

        TimeSlot timeSlot =
                timeSlotRepository
                        .findById(scheduleRequest.getTimeSlotId())
                        .orElseThrow(() -> new PreconditionException(ErrorCode.INVALID_TIMESLOT_ID));

        if(!isTimeslotAvailable(timeSlot, scheduleRequest.getScheduleDate(), branch)){
            throw new PreconditionException(ErrorCode.TIMESLOT_UNAVAILABLE);
        }

        Customer customer = customerRepository.save(new Customer(scheduleRequest.getCustomerName(),
                                                                 scheduleRequest.getCustomerNationalNumber(),
                                                                 scheduleRequest.getEmail()));
        VaccinationSchedule vaccinationSchedule =
                VaccinationSchedule
                        .builder()
                        .vaccinationScheduleCode(generateUniqueCode())
                        .scheduleDate(scheduleRequest.getScheduleDate())
                        .timeSlot(timeSlot)
                        .branch(branch)
                        .vaccine(vaccine)
                        .customer(customer)
                        .paymentMethod(paymentMethod)
                        .build();

        VaccinationSchedule response = vaccinationScheduleRepository.save(vaccinationSchedule);

        sendEmailConfirmation(response);

        return response;
    }

    private void sendEmailConfirmation(VaccinationSchedule vaccinationSchedule){
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(vaccinationSchedule.getCustomer().getEmail());
        msg.setSubject(appProperties.getEmailSubject());
        msg.setText(String.format(appProperties.getEmailContent(), vaccinationSchedule.getVaccinationScheduleCode()));
        mailSender.send(msg);
    }

    private boolean isTimeslotAvailable(TimeSlot timeSlot, LocalDate scheduleDate, Branch branch){
        Optional<VaccinationSchedule> schedule =
                vaccinationScheduleRepository.findSchedule(timeSlot, scheduleDate, branch);
        return !schedule.isPresent();
    }

    private String generateUniqueCode(){
        return UUID.randomUUID().toString();
    }

    /**
     *  Sets confirm flag to true
     */
    public void confirmSchedule(String scheduleCode){
        VaccinationSchedule schedule =
                vaccinationScheduleRepository
                        .findByVaccinationScheduleCode(scheduleCode)
                        .orElseThrow(() -> new PreconditionException(ErrorCode.INVALID_SCHEDULE));
        schedule.setConfirmed(true);
        vaccinationScheduleRepository.save(schedule);
    }

    /**
     *  Sets applied flag to true
     */
    public void markVaccinationAsApplied(String scheduleCode){
        VaccinationSchedule schedule =
                vaccinationScheduleRepository
                        .findByVaccinationScheduleCode(scheduleCode)
                        .orElseThrow(() -> new PreconditionException(ErrorCode.INVALID_SCHEDULE));
        schedule.setApplied(true);
        vaccinationScheduleRepository.save(schedule);
    }
}
